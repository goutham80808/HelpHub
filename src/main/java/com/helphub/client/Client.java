// src/main/java/com/helphub/client/Client.java
package com.helphub.client;

import com.helphub.common.Config;
import com.helphub.common.Message;
import com.helphub.common.Priority;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;

public class Client {
    private static String clientId;
    private static volatile ConnectionManager connectionManager;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--id")) {
            clientId = args[1];
        } else {
            clientId = "node-" + String.format("%06x", ThreadLocalRandom.current().nextInt(0, 0xFFFFFF + 1));
            System.out.println("No --id specified. Using generated ID: " + clientId);
        }
        System.out.println("HelpHub Client '" + clientId + "' starting...");

        Thread userInputThread = new Thread(new UserInputHandler());
        userInputThread.setDaemon(true);
        userInputThread.start();

        long currentBackoff = Config.getInt("reconnect.backoff.initial", 1000);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                connectionManager = new ConnectionManager(clientId);
                connectionManager.connectAndListen();
            } catch (Exception e) {
                System.err.println("Connection cycle ended: " + e.getMessage());
            }

            System.err.println("Disconnected. Retrying in " + (currentBackoff / 1000) + "s...");
            try {
                TimeUnit.MILLISECONDS.sleep(getJitteryBackoff(currentBackoff));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            currentBackoff = Math.min(currentBackoff * 2, Config.getInt("reconnect.backoff.max", 30000));
        }
    }

    private static long getJitteryBackoff(long baseDelay) {
        double jitter = Config.getDouble("reconnect.backoff.jitter", 0.2);
        double randomJitter = (ThreadLocalRandom.current().nextDouble(2.0) - 1.0) * jitter;
        return baseDelay + (long) (baseDelay * randomJitter);
    }

    private static class UserInputHandler implements Runnable {
        @Override
        public void run() {
            System.out.println("Commands: /to <id> <msg>, /all <msg>, /sos <msg>. Press Ctrl+C to exit.");
            try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
                String userInput;
                while ((userInput = consoleReader.readLine()) != null) {
                    if (connectionManager != null) {
                        connectionManager.handleUserInput(userInput);
                    } else {
                        System.out.println("Not connected. Message not sent.");
                    }
                }
            } catch (IOException e) { /* Console read failed */ }
        }
    }
}

class ConnectionManager {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 5000;
    private final String clientId;
    private Socket socket;
    private PrintWriter writer;
    private ScheduledExecutorService heartbeatExecutor;

    public ConnectionManager(String clientId) {
        this.clientId = clientId;
    }

    public void connectAndListen() throws IOException {
        try {
            System.setProperty("javax.net.ssl.trustStore", "helphub.keystore");
            System.setProperty("javax.net.ssl.trustStorePassword", "HelpHubPassword");

            SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = (SSLSocket) sf.createSocket(SERVER_ADDRESS, SERVER_PORT);

            ((SSLSocket) socket).startHandshake();

            writer = new PrintWriter(socket.getOutputStream(), true);
            System.out.println("Successfully connected to the HelpHub server.");
            writer.println(clientId);
            startHeartbeat();
            listenForMessages();
        } finally {
            disconnect();
        }
    }

    private void listenForMessages() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String serverJson;
            while ((serverJson = reader.readLine()) != null) {
                Message message = Message.fromJson(serverJson);
                if (message != null) {
                    writer.println(Message.createAck(clientId, message.getId()).toJson());
                    displayMessage(message);
                }
            }
        }
    }

    public void handleUserInput(String userInput) {
        if (writer == null || writer.checkError()) {
            System.out.println("Not connected. Please wait for reconnection.");
            return;
        }
        Message message;
        if (userInput.toLowerCase().startsWith("/sos ")) {
            message = new Message(Message.MessageType.BROADCAST, clientId, null, userInput.substring(5), Priority.HIGH);
        } else if (userInput.startsWith("/to ")) {
            String[] parts = userInput.split(" ", 3);
            if (parts.length == 3) {
                message = new Message(Message.MessageType.DIRECT, clientId, parts[1], parts[2], Priority.NORMAL);
            } else {
                System.out.println("Invalid format. Use: /to <recipientId> <message>");
                return;
            }
        } else {
            message = new Message(Message.MessageType.BROADCAST, clientId, null, userInput, Priority.NORMAL);
        }
        writer.println(message.toJson());
    }

    private void displayMessage(Message message) {
        String prefix = message.getType() == Message.MessageType.DIRECT ? "(Direct) " : "";
        boolean isDelayed = (System.currentTimeMillis() - message.getTimestamp()) > 60000;
        String delayedTag = isDelayed ? "(delayed) " : "";
        System.out.println(delayedTag + prefix + "From " + message.getFrom() + ": " + message.getBody());
    }

    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        int interval = Config.getInt("heartbeat.interval", 15000);
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (writer != null && !writer.checkError()) writer.println(Message.createHeartbeat(clientId).toJson());
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void disconnect() {
        System.out.println("Closing connection resources...");
        if (heartbeatExecutor != null) heartbeatExecutor.shutdownNow();
        try { if (writer != null) writer.close(); } catch (Exception e) { /* ignore */ }
        try { if (socket != null) socket.close(); } catch (IOException e) { /* ignore */ }
    }
}