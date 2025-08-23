// src/main/java/com/helphub/server/RelayServer.java
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RelayServer {
    private static final int PORT = 5000;
    private static final String LOG_FILE_PATH = "logs/messages.log";

    // A thread-safe Map to store client IDs and their corresponding writers.
    private final Map<String, PrintWriter> clientWriters = new ConcurrentHashMap<>();

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

    private void routeMessage(String jsonMessage) {
        logMessage(jsonMessage);
        Message message = Message.fromJson(jsonMessage);

        if (message == null) {
            System.err.println("Received malformed message, could not route.");
            return;
        }

        if (message.getType() == Message.MessageType.DIRECT) {
            // Direct message: send only to the recipient
            PrintWriter writer = clientWriters.get(message.getTo());
            if (writer != null) {
                writer.println(jsonMessage);
            } else {
                System.out.println("Client '" + message.getTo() + "' not found. Message from '" + message.getFrom() + "' dropped.");
                // Optional: send a 'user not found' status message back to the sender
            }
        } else {
            // Broadcast or Status message: send to everyone except the sender
            for (Map.Entry<String, PrintWriter> entry : clientWriters.entrySet()) {
                if (!entry.getKey().equals(message.getFrom())) {
                    entry.getValue().println(jsonMessage);
                }
            }
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
                // The first line from the client MUST be its client ID.
                this.clientId = reader.readLine();
                if (this.clientId == null || this.clientId.trim().isEmpty() || clientWriters.containsKey(this.clientId)) {
                    System.out.println("Client failed to provide a valid or unique ID. Disconnecting.");
                    return; // Close connection
                }

                this.writer = new PrintWriter(clientSocket.getOutputStream(), true);
                clientWriters.put(this.clientId, this.writer);
                System.out.println("Client '" + this.clientId + "' connected successfully.");

                // Process subsequent messages
                String jsonMessage;
                while ((jsonMessage = reader.readLine()) != null) {
                    System.out.println("Routing message from '" + this.clientId + "'");
                    routeMessage(jsonMessage);
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
    }
}