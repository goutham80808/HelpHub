package com.helphub.client;

import com.helphub.common.Config;
import com.helphub.common.Message;
import com.helphub.common.Priority;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConnectionManager {
    private static final int SERVER_PORT = 5000;
    private final String clientId;
    private final String serverAddress;
    private Socket socket;
    private PrintWriter writer;
    private ScheduledExecutorService heartbeatExecutor;

    public ConnectionManager(String clientId, String serverAddress) {
        this.clientId = clientId;
        this.serverAddress = serverAddress;
    }

    public void connectAndListen() throws IOException {
        try {
            System.setProperty("javax.net.ssl.trustStore", "helphub.keystore");
            System.setProperty("javax.net.ssl.trustStorePassword", "HelpHubPassword");

            SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = (SSLSocket) sf.createSocket(this.serverAddress, SERVER_PORT);

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