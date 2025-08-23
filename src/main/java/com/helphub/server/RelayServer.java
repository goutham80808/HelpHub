package com.helphub.server;

import com.helphub.common.Config;
import com.helphub.common.Message;

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
        startConnectionCleanupTask();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server is listening for connections.");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                new Thread(handler).start();
            }
        } catch (IOException e) {
            System.err.println("Error starting the server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startConnectionCleanupTask() {
        long timeout = Config.getInt("connection.timeout", 45000);
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            System.out.println("Running connection cleanup task...");
            clientHandlers.values().forEach(handler -> {
                if (now - handler.getLastHeartbeatTime() > timeout) {
                    System.out.println("Client '" + handler.getClientId() + "' timed out. Disconnecting.");
                    handler.disconnect();
                }
            });
        }, timeout, timeout, TimeUnit.MILLISECONDS);
    }

    private void routeMessage(Message message) {
        // Log to console for the admin
        String to = (message.getTo() == null) ? "ALL" : message.getTo();
        System.out.printf("[MSG] [FROM:%s] -> [TO:%s]: %s%n", message.getFrom(), to, message.getBody());

        // Step 1: Always store the message in the database for durability.
        database.storeMessage(message);

        // Step 2: Attempt immediate delivery to online clients.
        if (message.getType() == Message.MessageType.DIRECT) {
            ClientHandler handler = clientHandlers.get(message.getTo());
            if (handler != null) {
                handler.sendMessage(message.toJson());
                System.out.println(" -> Delivered direct message to online client: " + message.getTo());
            } else {
                System.out.println(" -> Queued direct message for offline client: " + message.getTo());
            }
        } else if (message.getType() == Message.MessageType.BROADCAST) {
            for (ClientHandler handler : clientHandlers.values()) {
                if (!handler.getClientId().equals(message.getFrom())) {
                    handler.sendMessage(message.toJson());
                }
            }
            System.out.println(" -> Broadcast message sent to all online clients.");
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private String clientId;
        private PrintWriter writer;
        private volatile long lastHeartbeatTime;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        public String getClientId() { return clientId; }
        public long getLastHeartbeatTime() { return lastHeartbeatTime; }

        // Helper method to send a message to this specific client
        public void sendMessage(String jsonMessage) {
            if (writer != null) {
                writer.println(jsonMessage);
            }
        }

        @Override
        public void run() {
            this.lastHeartbeatTime = System.currentTimeMillis();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                this.clientId = reader.readLine();
                if (clientId == null || clientId.trim().isEmpty()) { return; }

                this.writer = new PrintWriter(clientSocket.getOutputStream(), true);
                clientHandlers.put(this.clientId, this);
                database.updateClientLastSeen(clientId);
                System.out.println("Client '" + this.clientId + "' connected. Checking for pending messages...");
                flushPendingMessages();

                String jsonMessage;
                while ((jsonMessage = reader.readLine()) != null) {
                    Message message = Message.fromJson(jsonMessage);
                    if (message == null) continue;

                    this.lastHeartbeatTime = System.currentTimeMillis(); // Any message counts as activity

                    if (message.getType() == Message.MessageType.HEARTBEAT) {
                        database.updateClientLastSeen(clientId); // Update DB on heartbeat
                    } else if (message.getType() == Message.MessageType.ACK) {
                        database.updateMessageStatus(message.getBody(), "DELIVERED");
                    } else {
                        routeMessage(message);
                    }
                }
            } catch (IOException e) {
                // Connection lost
            } finally {
                disconnect();
            }
        }

        public void disconnect() {
            if (this.clientId != null) {
                clientHandlers.remove(this.clientId);
                System.out.println("Client '" + this.clientId + "' disconnected. Total clients: " + clientHandlers.size());
            }
            try {
                if (!clientSocket.isClosed()) clientSocket.close();
            } catch (IOException e) { /* ignore */ }
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