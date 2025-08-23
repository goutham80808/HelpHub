package com.helphub.server;

import com.helphub.common.Message;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RelayServer {
    private static final int PORT = 5000;
    private static final String LOG_FILE_PATH = "logs/messages.log";

    private final Map<String, PrintWriter> clientWriters = new ConcurrentHashMap<>();
    private final Db database;

    public RelayServer() {
        this.database = new Db(); // Initialize the database
    }

    public static void main(String[] args) {
        new RelayServer().startServer();
    }

    public void startServer() {
        System.out.println("HelpHub Relay Server starting on port " + PORT + "...");
        setupLogging();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server is listening for connections.");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connecting: " + clientSocket.getRemoteSocketAddress());
                new Thread(new ClientHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Error starting the server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void setupLogging() {
        try {
            Files.createDirectories(Paths.get("logs"));
            if (!Files.exists(Paths.get(LOG_FILE_PATH))) {
                Files.createFile(Paths.get(LOG_FILE_PATH));
            }
        } catch (IOException e) {
            System.err.println("Failed to set up log file: " + e.getMessage());
        }
    }

    private void logMessage(String jsonMessage) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logEntry = timestamp + " | " + jsonMessage + System.lineSeparator();
        try {
            Files.write(Paths.get(LOG_FILE_PATH), logEntry.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }

    private void routeMessage(Message message) {
        logMessage(message.toJson());

        // Step 1: Always store the message in the database first for durability.
        database.storeMessage(message);

        // Step 2: Attempt immediate delivery to online clients.
        if (message.getType() == Message.MessageType.DIRECT) {
            PrintWriter writer = clientWriters.get(message.getTo());
            if (writer != null) {
                writer.println(message.toJson());
                System.out.println("Delivered direct message to online client: " + message.getTo());
            } else {
                System.out.println("Queued direct message for offline client: " + message.getTo());
            }
        } else if (message.getType() == Message.MessageType.BROADCAST) {
            for (Map.Entry<String, PrintWriter> entry : clientWriters.entrySet()) {
                if (!entry.getKey().equals(message.getFrom())) {
                    entry.getValue().println(message.toJson());
                }
            }
            System.out.println("Broadcast message sent to all online clients.");
        }
    }

    /**
     * Handles communication for a single connected client.
     */
    private class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private String clientId;
        private PrintWriter writer;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            ) {
                // First line is the client ID
                this.clientId = reader.readLine();
                if (this.clientId == null || this.clientId.trim().isEmpty() || clientWriters.containsKey(this.clientId)) {
                    System.out.println("Client failed to provide a valid or unique ID. Disconnecting.");
                    return;
                }

                this.writer = new PrintWriter(clientSocket.getOutputStream(), true);
                clientWriters.put(this.clientId, this.writer);
                System.out.println("Client '" + this.clientId + "' connected. Checking for pending messages...");

                // --- NEW: OFFLINE MESSAGE REPLAY ---
                flushPendingMessages();

                // Process subsequent messages from the client
                String jsonMessage;
                while ((jsonMessage = reader.readLine()) != null) {
                    Message message = Message.fromJson(jsonMessage);
                    if (message == null) continue;

                    if (message.getType() == Message.MessageType.ACK) {
                        // Client is acknowledging receipt of a message
                        database.updateMessageStatus(message.getBody(), "DELIVERED");
                        System.out.println("Received ACK for message " + message.getBody() + " from " + clientId);
                    } else {
                        // It's a regular message to be routed
                        routeMessage(message);
                    }
                }
            } catch (IOException e) {
                System.out.println("Client '" + this.clientId + "' disconnected.");
            } finally {
                // Clean up resources for this client.
                if (this.clientId != null) {
                    clientWriters.remove(this.clientId);
                    System.out.println("Client '" + this.clientId + "' removed. Total clients: " + clientWriters.size());
                }
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        private void flushPendingMessages() {
            List<Message> pending = database.getPendingMessagesForClient(clientId);
            if (!pending.isEmpty()) {
                System.out.println("Sending " + pending.size() + " pending messages to " + clientId);
                for (Message msg : pending) {
                    writer.println(msg.toJson());
                }
            }
        }
    }
}