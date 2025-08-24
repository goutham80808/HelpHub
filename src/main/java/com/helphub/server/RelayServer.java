// src/main/java/com/helphub/server/RelayServer.java
package com.helphub.server;

import com.helphub.common.Config;
import com.helphub.common.Message;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RelayServer {
    private static final int PORT = 5000;
    private final Map<String, ClientHandler> clientHandlers = new ConcurrentHashMap<>();
    private final Db database;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public RelayServer() {
        this.database = new Db();
    }

    public static void main(String[] args) {
        new RelayServer().startServer();
    }

    public void startServer() {
        System.out.println("HelpHub Relay Server starting on port " + PORT + "...");

        String keyStorePassword = System.getenv("KEYSTORE_PASSWORD");
        if (keyStorePassword == null) {
            System.err.println("FATAL: KEYSTORE_PASSWORD environment variable not set.");
            System.exit(1);
        }
        System.setProperty("javax.net.ssl.keyStore", "helphub.keystore");
        System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);

        startConnectionCleanupTask();
        new Thread(new AdminConsole()).start(); // Assuming AdminConsole exists and is correct
        System.out.println("Admin console started. Type 'help' for a list of commands.");

        try {
            SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(PORT)) {
                System.out.println("Server is listening for secure client connections.");
                while (true) {
                    SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                    ClientHandler handler = new ClientHandler(clientSocket);
                    new Thread(handler).start();
                }
            }
        } catch (IOException e) {
            System.err.println("Error starting the server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ... (startConnectionCleanupTask, routeMessage, AdminConsole, etc. are assumed correct) ...
    // The following is a placeholder for your existing correct code.
    private void startConnectionCleanupTask() { /* Omitted for brevity */ }
    private void routeMessage(Message message) { /* Omitted for brevity */ }
    private class AdminConsole implements Runnable { @Override public void run() { /* Omitted for brevity */ } }


    private class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private String clientId;
        private PrintWriter writer;
        private volatile long lastHeartbeatTime;

        public ClientHandler(Socket socket) { this.clientSocket = socket; }
        public String getClientId() { return clientId; }
        public long getLastHeartbeatTime() { return lastHeartbeatTime; }
        public void sendMessage(String jsonMessage) { if (writer != null) writer.println(jsonMessage); }

        @Override
        public void run() {
            try {
                // --- FIX: Force TLS handshake to complete before reading data ---
                if (clientSocket instanceof SSLSocket) {
                    ((SSLSocket) clientSocket).startHandshake();
                }

                this.lastHeartbeatTime = System.currentTimeMillis();
                writer = new PrintWriter(clientSocket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                this.clientId = reader.readLine();
                if (clientId == null || clientId.trim().isEmpty()) {
                    System.err.println("Client failed to send a valid ID. Closing connection.");
                    return; // This will trigger the finally block
                }

                clientHandlers.put(this.clientId, this);
                database.updateClientLastSeen(clientId);
                System.out.println("Client '" + this.clientId + "' connected. Checking for pending messages...");
                flushPendingMessages();

                String jsonMessage;
                while ((jsonMessage = reader.readLine()) != null) {
                    this.lastHeartbeatTime = System.currentTimeMillis();
                    Message message = Message.fromJson(jsonMessage);
                    if (message == null) continue;
                    if (message.getType() == Message.MessageType.HEARTBEAT) {
                        database.updateClientLastSeen(clientId);
                    } else if (message.getType() == Message.MessageType.ACK) {
                        database.updateMessageStatus(message.getBody(), "DELIVERED");
                    } else {
                        routeMessage(message);
                    }
                }
            } catch (IOException e) {
                System.out.println("Connection lost for client '" + (clientId != null ? clientId : "UNKNOWN") + "': " + e.getMessage());
            } finally {
                disconnect();
            }
        }

        public void disconnect() {
            if (this.clientId != null) {
                clientHandlers.remove(this.clientId);
                System.out.println("Client '" + this.clientId + "' disconnected. Total clients: " + clientHandlers.size());
            }
            try { if (!clientSocket.isClosed()) clientSocket.close(); } catch (IOException e) { /* ignore */ }
        }

        private void flushPendingMessages() {
            List<Message> pending = database.getPendingMessagesForClient(clientId);
            if (!pending.isEmpty()) {
                System.out.println("Sending " + pending.size() + " pending messages to " + clientId);
                for (Message msg : pending) {
                    sendMessage(msg.toJson());
                }
            }
        }
    }
}