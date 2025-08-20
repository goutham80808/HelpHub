package com.helphub.server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RelayServer {
    private static final int PORT = 5000;
    private static final String LOG_FILE_PATH = "logs/messages.log";

    // A thread-safe set to store writer streams of all connected clients.
    private final Set<PrintWriter> clientWriters = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) {
        new RelayServer().startServer();
    }

    public void startServer() {
        System.out.println("HelpHub Relay Server starting on port " + PORT + "...");
        setupLogging();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server is listening for connections.");
            while (true) {
                // Accept a new client connection. This call blocks until a connection is made.
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getRemoteSocketAddress());

                // Create a handler for the new client and start it in a new thread.
                ClientHandler handler = new ClientHandler(clientSocket);
                new Thread(handler).start();
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

    private void logMessage(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logEntry = timestamp + " | " + message + System.lineSeparator();
        try {
            Files.write(Paths.get(LOG_FILE_PATH), logEntry.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }

    private void broadcastMessage(String message, PrintWriter excludeWriter) {
        logMessage(message);
        for (PrintWriter writer : clientWriters) {
            if (writer != excludeWriter) {
                writer.println(message);
            }
        }
    }

    /**
     * Handles communication for a single connected client in its own thread.
     */
    private class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private PrintWriter writer;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (
                    InputStream input = clientSocket.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(input));
                    OutputStream output = clientSocket.getOutputStream();
            ) {
                this.writer = new PrintWriter(output, true);
                clientWriters.add(writer);

                String clientMessage;
                while ((clientMessage = reader.readLine()) != null) {
                    System.out.println("Received from " + clientSocket.getRemoteSocketAddress() + ": " + clientMessage);
                    broadcastMessage(clientMessage, writer);
                }

            } catch (IOException e) {
                // This exception typically occurs when the client disconnects.
                System.out.println("Client " + clientSocket.getRemoteSocketAddress() + " disconnected.");
            } finally {
                // Clean up resources for this client.
                if (writer != null) {
                    clientWriters.remove(writer);
                }
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
            }
        }
    }
}