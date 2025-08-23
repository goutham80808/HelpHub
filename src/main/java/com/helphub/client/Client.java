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
        // --- FIX: Get a reference to the current thread (the main thread) ---
        Thread mainClientThread = Thread.currentThread();

        try (
                Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))
        ) {
            System.out.println("Successfully connected to the HelpHub server.");
            writer.println(clientId);
            startHeartbeat(writer);

            // --- FIX: Pass the main thread reference to the listener ---
            new Thread(new ServerListener(socket, writer, mainClientThread)).start();

            System.out.println("Commands: /to <id> <msg>, /all <msg>. Press Ctrl+C to exit.");

            String userInput;
            // --- FIX: Catch the InterruptedException that the listener will trigger ---
            try {
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
            } catch (IOException e) {
                // This will catch the interrupt and allow us to exit runClient gracefully
                // Check if the cause is an InterruptedIOException, which is what readLine() throws on interrupt
                if (e instanceof InterruptedIOException || (e.getCause() != null && e.getCause() instanceof InterruptedException)) {
                    // This is the expected interruption from the listener thread
                    throw new IOException("Connection listener failed, triggering reconnect.", e);
                }
                // Rethrow other unexpected IOExceptions
                throw e;
            }
        } finally {
            stopHeartbeat();
        }
    }

    private static void startHeartbeat(PrintWriter writer) {
        stopHeartbeat(); // Ensure any previous task is cancelled
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        int interval = Config.getInt("heartbeat.interval", 15000);

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!writer.checkError()) {
                writer.println(Message.createHeartbeat(clientId).toJson());
            } else {
                // If writer has an error, the pipe is broken. Interrupt the main thread.
                Thread.currentThread().getThreadGroup().getParent().interrupt();
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
        // --- FIX: Add a field for the main thread ---
        private final Thread mainThreadToInterrupt;

        // --- FIX: Update constructor to accept the main thread ---
        public ServerListener(Socket socket, PrintWriter writer, Thread mainThreadToInterrupt) {
            this.socket = socket;
            this.writer = writer;
            this.mainThreadToInterrupt = mainThreadToInterrupt;
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
                // This is the critical failure point. The server connection is gone.
                // --- FIX: INTERRUPT THE MAIN THREAD TO UN-BLOCK IT ---
                System.err.println("Listener detected connection loss. Signalling main thread to reconnect...");
                mainThreadToInterrupt.interrupt();
            }
        }
    }
}