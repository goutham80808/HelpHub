// src/main/java/com/helphub/client/Client.java
package com.helphub.client;

import com.helphub.common.Message;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

public class Client {
    private static final String SERVER_ADDRESS = "localhost"; // Change to server's LAN IP for hotspot test
    private static final int SERVER_PORT = 5000;
    private static String clientId;

    public static void main(String[] args) {
        // Parse client ID from arguments, or generate a random one
        if (args.length > 0 && args[0].equals("--id")) {
            clientId = args[1];
        } else {
            int randomNum = ThreadLocalRandom.current().nextInt(0, 0xFFFFFF + 1);
            clientId = "node-" + String.format("%06x", randomNum);
            System.out.println("No --id specified. Using generated ID: " + clientId);
        }

        System.out.println("HelpHub Client '" + clientId + "' starting...");

        try (
                Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))
        ) {
            // First, identify the client to the server
            writer.println(clientId);

            System.out.println("Connected to the HelpHub server. Commands: /to <id> <msg>, /all <msg>");
            System.out.println("Press Ctrl+C to exit.");

            // Start thread to listen for server messages
            new Thread(new ServerListener(socket)).start();

            // Shutdown hook for clean exit
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down client...");
                try { socket.close(); } catch (IOException e) { /* ignore */ }
            }));

            // Main thread loop to read console input
            String userInput;
            while ((userInput = consoleReader.readLine()) != null) {
                Message message;
                if (userInput.startsWith("/to ")) {
                    String[] parts = userInput.split(" ", 3); // /to, <id>, <msg>
                    if (parts.length == 3) {
                        message = new Message(Message.MessageType.DIRECT, clientId, parts[1], parts[2]);
                        writer.println(message.toJson());
                    } else {
                        System.out.println("Invalid format. Use: /to <recipientId> <message>");
                    }
                } else if (userInput.startsWith("/all ")) {
                    String body = userInput.substring(5); // a/l/l/ /
                    message = new Message(Message.MessageType.BROADCAST, clientId, null, body);
                    writer.println(message.toJson());
                } else {
                    // Default to broadcast for simple messages
                    message = new Message(Message.MessageType.BROADCAST, clientId, null, userInput);
                    writer.println(message.toJson());
                }
            }

        } catch (UnknownHostException ex) {
            System.err.println("Server not found: " + ex.getMessage());
        } catch (IOException ex) {
            System.err.println("I/O error or connection lost: " + ex.getMessage());
        } finally {
            System.out.println("Client disconnected.");
        }
    }

    private static class ServerListener implements Runnable {
        private final Socket socket;

        public ServerListener(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                String serverJson;
                while ((serverJson = reader.readLine()) != null) {
                    Message message = Message.fromJson(serverJson);
                    if (message != null) {
                        String prefix = message.getType() == Message.MessageType.DIRECT ? "(Direct) " : "";
                        System.out.println(prefix + "From " + message.getFrom() + ": " + message.getBody());
                    } else {
                        System.out.println("Received unreadable message: " + serverJson);
                    }
                }
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    System.err.println("Connection to server lost.");
                }
            }
        }
    }
}