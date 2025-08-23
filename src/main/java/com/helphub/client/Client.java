// src/main/java/com/helphub/client/Client.java
package com.helphub.client;

import com.helphub.common.Config;
import com.helphub.common.Message;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class Client {
    private static final String SERVER_ADDRESS = "localhost"; // Change to server's LAN IP for hotspot test
    private static final int SERVER_PORT = 5000;
    private static String clientId;
    private static ScheduledExecutorService heartbeatExecutor;

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

        // Add a shutdown hook to cleanly close resources on Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown requested. Exiting...");
            stopHeartbeat();
        }));

        // --- AUTO-RECONNECT LOOP ---
        long currentBackoff = Config.getInt("reconnect.backoff.initial", 1000);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // This method will block until the connection is lost or the user exits
                runClient();

                // If runClient returns normally (e.g., console input ends), break the loop
                System.out.println("Client shut down gracefully.");
                break;
            } catch (IOException e) {
                System.err.println("Connection lost: " + e.getMessage() + ". Retrying in " + (currentBackoff / 1000) + "s...");
                try {
                    TimeUnit.MILLISECONDS.sleep(getJitteryBackoff(currentBackoff));
                } catch (InterruptedException interruptedException) {
                    System.out.println("Reconnect attempt interrupted. Exiting.");
                    Thread.currentThread().interrupt(); // Preserve the interrupted status
                    break;
                }
                // Increase backoff for the next attempt
                currentBackoff = Math.min(currentBackoff * 2, Config.getInt("reconnect.backoff.max", 30000));
            }
        }
        System.out.println("Client has been shut down.");
    }

    /**
     * Establishes a connection and handles the main logic for a single session.
     * Throws IOException on connection failure, which is caught by the main reconnect loop.
     */
    private static void runClient() throws IOException {
        try (
                Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))
        ) {
            System.out.println("Successfully connected to the HelpHub server.");

            // First, identify the client to the server
            writer.println(clientId);

            // Start sending periodic heartbeats
            startHeartbeat(writer);

            // Start a separate thread to listen for messages from the server
            new Thread(new ServerListener(socket, writer)).start();

            System.out.println("Commands: /to <id> <msg>, /all <msg>. Press Ctrl+C to exit.");

            // Main thread loop to read console input and send to server
            String userInput;
            while ((userInput = consoleReader.readLine()) != null) {
                Message message;
                if (userInput.startsWith("/to ")) {
                    String[] parts = userInput.split(" ", 3);
                    if (parts.length == 3) {
                        message = new Message(Message.MessageType.DIRECT, clientId, parts[1], parts[2]);
                        writer.println(message.toJson());
                    } else {
                        System.out.println("Invalid format. Use: /to <recipientId> <message>");
                    }
                } else if (userInput.startsWith("/all ")) {
                    String body = userInput.substring(5);
                    message = new Message(Message.MessageType.BROADCAST, clientId, null, body);
                    writer.println(message.toJson());
                } else {
                    message = new Message(Message.MessageType.BROADCAST, clientId, null, userInput);
                    writer.println(message.toJson());
                }
            }
        } finally {
            // This block executes when the try-with-resources closes, i.e., on disconnect
            stopHeartbeat();
        }
    }

    private static void startHeartbeat(PrintWriter writer) {
        stopHeartbeat(); // Ensure any previous task is cancelled
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        int interval = Config.getInt("heartbeat.interval", 15000);

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            // Don't wrap in try-catch; we WANT this to fail if the connection is dead
            // so the main loop can detect it and start the reconnect process.
            if (!writer.checkError()) {
                writer.println(Message.createHeartbeat(clientId).toJson());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private static void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdownNow(); // Use shutdownNow to interrupt the task
        }
    }

    private static long getJitteryBackoff(long baseDelay) {
        double jitter = Config.getDouble("reconnect.backoff.jitter", 0.2);
        // Calculate a random jitter value between -jitter and +jitter
        double randomJitter = (ThreadLocalRandom.current().nextDouble(2.0) - 1.0) * jitter;
        long jitterAmount = (long) (baseDelay * randomJitter);
        return baseDelay + jitterAmount;
    }

    /**
     * A runnable task that continuously listens for messages from the server
     * and prints them to the console, while also sending ACKs.
     */
    private static class ServerListener implements Runnable {
        private final Socket socket;
        private final PrintWriter writer;

        public ServerListener(Socket socket, PrintWriter writer) {
            this.socket = socket;
            this.writer = writer;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                String serverJson;
                while ((serverJson = reader.readLine()) != null) {
                    Message message = Message.fromJson(serverJson);
                    if (message != null) {
                        // Immediately send an ACK back to the server
                        Message ack = Message.createAck(clientId, message.getId());
                        writer.println(ack.toJson());

                        // Display the message to the user
                        String prefix = message.getType() == Message.MessageType.DIRECT ? "(Direct) " : "";
                        boolean isDelayed = (System.currentTimeMillis() - message.getTimestamp()) > 60000;
                        String delayedTag = isDelayed ? "(delayed) " : "";

                        System.out.println(delayedTag + prefix + "From " + message.getFrom() + ": " + message.getBody());
                    } else {
                        System.out.println("Received unreadable message: " + serverJson);
                    }
                }
            } catch (IOException e) {
                // This exception is expected when the socket is closed by the main thread
                // or when the server connection is lost. The main reconnect loop will handle it.
                if (!socket.isClosed()) {
                    // We don't print an error here anymore because the main loop does.
                }
            }
        }
    }
}