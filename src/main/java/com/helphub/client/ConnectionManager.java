package com.helphub.client;

import com.helphub.common.Config;
import com.helphub.common.Message;
import com.helphub.common.Priority;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the entire lifecycle of a single, secure connection to the RelayServer.
 * <p>
 * This class is instantiated for each connection attempt. It handles connecting,
 * TLS handshaking, listening for messages, processing user input for sending,
 * sending heartbeats, and cleaning up resources upon disconnection.
 */
public class ConnectionManager {
    private static final int SERVER_PORT = 5000;
    private final String clientId;
    private final String serverAddress;

    private Socket socket;
    private PrintWriter writer;
    private ScheduledExecutorService heartbeatExecutor;

    /**
     * Constructs a new ConnectionManager for a single connection attempt.
     * @param clientId The unique ID for this client.
     * @param serverAddress The IP address of the RelayServer.
     */
    public ConnectionManager(String clientId, String serverAddress) {
        this.clientId = clientId;
        this.serverAddress = serverAddress;
    }

    /**
     * The main blocking method for a connection. It establishes a secure connection,
     * starts the heartbeat, and then enters a loop to listen for incoming messages
     * until the connection is lost, at which point it cleans up.
     * @throws IOException if the initial connection or handshake fails.
     */
    public void connectAndListen() throws IOException {
        try {
            // Set system properties to configure the client's SSL context.
            // This tells the JVM where to find the keystore to trust the server's self-signed certificate.
            System.setProperty("javax.net.ssl.trustStore", "helphub.keystore");
            System.setProperty("javax.net.ssl.trustStorePassword", "HelpHubPassword");

            SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = sf.createSocket(this.serverAddress, SERVER_PORT);

            // Force the TLS handshake to complete before sending any application data.
            // This prevents race conditions where we might try to write data before the secure channel is ready.
            ((SSLSocket) socket).startHandshake();

            writer = new PrintWriter(socket.getOutputStream(), true);
            System.out.println("Successfully connected to the HelpHub server.");

            // The first action after connecting is to send our ID to the server for registration.
            writer.println(clientId);
            startHeartbeat();
            listenForMessages();
        } finally {
            // This block is guaranteed to run whether the connection ends normally or with an error.
            disconnect();
        }
    }

    /**
     * Handles user input from the main client thread, parsing it into a {@link Message}
     * object and sending it to the server in JSON format.
     * @param userInput The raw text entered by the user.
     */
    public void handleUserInput(String userInput) {
        // A checkError() call on the writer is a lightweight way to see if the socket has been closed.
        if (writer == null || writer.checkError()) {
            System.out.println("Not connected. Please wait for reconnection.");
            return;
        }

        Message message;
        if (userInput.toLowerCase().startsWith("/sos ")) {
            message = new Message(Message.MessageType.BROADCAST, clientId, null, userInput.substring(5), Priority.HIGH);
        } else if (userInput.startsWith("/to ")) {
            // This parsing logic is designed to correctly handle recipient IDs that may contain hyphens.
            try {
                int firstSpaceIndex = userInput.indexOf(' ');
                int secondSpaceIndex = userInput.indexOf(' ', firstSpaceIndex + 1);
                if (secondSpaceIndex == -1) throw new IllegalArgumentException("Message body is missing.");

                String recipientId = userInput.substring(firstSpaceIndex + 1, secondSpaceIndex);
                String body = userInput.substring(secondSpaceIndex + 1);

                if (recipientId.isEmpty() || body.isEmpty()) throw new IllegalArgumentException("Recipient or body is empty.");

                message = new Message(Message.MessageType.DIRECT, clientId, recipientId, body, Priority.NORMAL);
            } catch (Exception e) {
                System.out.println("Invalid format. Use: /to <recipientId> <message>");
                return;
            }
        } else {
            // Any other input is treated as a normal-priority broadcast message.
            message = new Message(Message.MessageType.BROADCAST, clientId, null, userInput, Priority.NORMAL);
        }
        writer.println(message.toJson());
    }

    /**
     * Enters a blocking loop to read incoming lines from the server socket.
     * For each valid message received, it sends an ACK and displays the message.
     * @throws IOException if the connection is terminated.
     */
    private void listenForMessages() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String serverJson;
            while ((serverJson = reader.readLine()) != null) {
                Message message = Message.fromJson(serverJson);
                if (message != null) {
                    // Acknowledge receipt of the message immediately.
                    writer.println(Message.createAck(clientId, message.getId()).toJson());
                    displayMessage(message);
                }
            }
        }
    }

    /**
     * Formats and prints a received message to the console.
     * Prepends a "(delayed)" tag for messages that were queued for over a minute.
     * @param message The message to display.
     */
    private void displayMessage(Message message) {
        String prefix = message.getType() == Message.MessageType.DIRECT ? "(Direct) " : "";
        boolean isDelayed = (System.currentTimeMillis() - message.getTimestamp()) > 60000; // 60 seconds
        String delayedTag = isDelayed ? "(delayed) " : "";
        System.out.println(delayedTag + prefix + "From " + message.getFrom() + ": " + message.getBody());
    }

    /**
     * Starts a scheduled task to send a heartbeat message to the server at a regular interval.
     */
    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        int interval = Config.getInt("heartbeat.interval", 15000);
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (writer != null && !writer.checkError()) {
                writer.println(Message.createHeartbeat(clientId).toJson());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Cleans up all resources associated with the connection.
     * Shuts down the heartbeat, and safely closes the writer and socket.
     */
    private void disconnect() {
        System.out.println("Closing connection resources...");
        // Stop the heartbeat thread immediately.
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        // Safely close the I/O streams and socket.
        try { if (writer != null) writer.close(); } catch (Exception e) { /* ignore */ }
        try { if (socket != null) socket.close(); } catch (IOException e) { /* ignore */ }
    }
}