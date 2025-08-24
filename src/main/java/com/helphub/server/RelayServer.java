// FILE: src/main/java/com/helphub/server/RelayServer.java
package com.helphub.server;

import com.helphub.common.Config;
import com.helphub.common.Message;
import com.helphub.common.Priority;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RelayServer {
    private static final int PORT = 5000;
    private static final int ADMIN_PORT = 5001; // NEW ADMIN PORT

    private static final String LOG_FILE_PATH = "logs/messages.log";
    private final Map<String, ClientHandler> clientHandlers = new ConcurrentHashMap<>();
    private final Db database;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public RelayServer() { this.database = new Db(); }
    public static void main(String[] args) { new RelayServer().startServer(); }

    public void startServer() {
        System.out.println("HelpHub Relay Server starting on port " + PORT + "...");

        String keyStorePassword = System.getenv("KEYSTORE_PASSWORD");
        if (keyStorePassword == null) {
            System.err.println("FATAL: KEYSTORE_PASSWORD environment variable not set.");
            System.exit(1);
        }
        System.setProperty("javax.net.ssl.keyStore", "helphub.keystore");
        System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);

        startConnectionCleanupTask();
        Thread adminConsoleThread = new Thread(new AdminConsole());
        adminConsoleThread.setDaemon(true);
        adminConsoleThread.start();
        System.out.println("Admin console started. Type 'help' for a list of commands.");
        startAdminDataListener();
        try {
            SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(PORT)) {
                System.out.println("Server is listening for secure client connections.");
                while (true) {
                    SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                    ClientHandler handler = new ClientHandler(clientSocket);
                    new Thread(handler).start();
                }
            }
        } catch (IOException e) {
            System.err.println("Error in server socket listener: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startAdminDataListener() {
        new Thread(() -> {
            try (ServerSocket adminSocket = new ServerSocket(ADMIN_PORT)) {
                System.out.println("Dashboard listener started on port " + ADMIN_PORT);
                while (true) {
                    Socket dashboardConnection = adminSocket.accept();
                    new Thread(new AdminConnectionHandler(dashboardConnection)).start();
                }
            } catch (IOException e) {
                System.err.println("FATAL: Could not start dashboard listener: " + e.getMessage());
            }
        }).start();
    }

    private void startConnectionCleanupTask() {
        long timeout = Config.getInt("connection.timeout", 45000);
        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("Running connection cleanup task...");
            long now = System.currentTimeMillis();
            clientHandlers.values().forEach(handler -> {
                if (now - handler.getLastHeartbeatTime() > timeout) {
                    System.out.println("Client '" + handler.getClientId() + "' timed out. Disconnecting.");
                    handler.disconnect();
                }
            });
        }, timeout, timeout, TimeUnit.MILLISECONDS);
    }

    private void routeMessage(Message message) {
        String to = (message.getTo() == null) ? "ALL" : message.getTo();
        System.out.printf("[MSG] [FROM:%s] -> [TO:%s]: %s%n", message.getFrom(), to, message.getBody());
        database.storeMessage(message);

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

        public ClientHandler(Socket socket) { this.clientSocket = socket; }
        public String getClientId() { return clientId; }
        public long getLastHeartbeatTime() { return lastHeartbeatTime; }
        public void sendMessage(String jsonMessage) { if (writer != null) writer.println(jsonMessage); }

        @Override
        public void run() {
            try {
                if (clientSocket instanceof SSLSocket) {
                    ((SSLSocket) clientSocket).startHandshake();
                }

                this.lastHeartbeatTime = System.currentTimeMillis();
                this.writer = new PrintWriter(clientSocket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                this.clientId = reader.readLine();
                if (clientId == null || clientId.trim().isEmpty()) {
                    System.err.println("Client connected but failed to send a valid ID. Closing connection.");
                    return;
                }

                clientHandlers.put(this.clientId, this);
                database.updateClientLastSeen(clientId);
                System.out.println("Client '" + this.clientId + "' connected. Checking for pending messages...");
                flushPendingMessages();

                String jsonMessage;
                while ((jsonMessage = reader.readLine()) != null) {
                    this.lastHeartbeatTime = System.currentTimeMillis();
                    Message message = Message.fromJson(jsonMessage);
                    if (message == null) continue;

                    if (message.getType() == Message.MessageType.HEARTBEAT) {
                        database.updateClientLastSeen(clientId);
                    } else if (message.getType() == Message.MessageType.ACK) {
                        database.updateMessageStatus(message.getBody(), "DELIVERED");
                    } else {
                        routeMessage(message);
                    }
                }
            } catch (IOException e) {
                System.out.println("Connection lost for client '" + (clientId != null ? clientId : "UNKNOWN") + "': " + e.getMessage());
            } finally {
                disconnect();
            }
        }

        public void disconnect() {
            if (this.clientId != null) {
                clientHandlers.remove(this.clientId);
                System.out.println("Client '" + this.clientId + "' disconnected. Total clients: " + clientHandlers.size());
            }
            try { if (!clientSocket.isClosed()) clientSocket.close(); } catch (IOException e) { /* ignore */ }
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

    /**
     * Handles a single dashboard connection, responding to data requests.
     * NOW INCLUDES an authentication check.
     */
    private class AdminConnectionHandler implements Runnable {
        private final Socket dashboardSocket;

        public AdminConnectionHandler(Socket socket) { this.dashboardSocket = socket; }

        @Override
        public void run() {
            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(dashboardSocket.getInputStream()));
                    PrintWriter writer = new PrintWriter(dashboardSocket.getOutputStream(), true)
            ) {
                // --- NEW: SECURITY CHECK ---
                String providedPassword = reader.readLine();
                String expectedPassword = System.getenv("ADMIN_PASSWORD");

                if (expectedPassword == null || expectedPassword.isEmpty() || !expectedPassword.equals(providedPassword)) {
                    System.out.println("Dashboard connection failed: Invalid admin password.");
                    writer.println("ERROR:AUTH_FAILED");
                    return; // Close the connection
                }
                // --- END SECURITY CHECK ---

                String commandLine = reader.readLine();
                if (commandLine == null) return;

                String[] parts = commandLine.split("\\s+", 2); // Split into command and the rest
                String command = parts[0];

                switch (command) {
                    case "GET_DATA":
                        writer.println(buildStateAsJson());
                        break;
                    case "GET_PENDING":
                        if (parts.length > 1) writer.println(buildPendingMessagesAsJson(parts[1]));
                        break;
                    case "ADMIN_BROADCAST":
                        if (parts.length > 1) handleAdminBroadcast(parts[1]);
                        break;
                    case "ADMIN_KICK":
                        if (parts.length > 1) handleAdminKick(parts[1]);
                        break;
                }
            } catch (IOException e) {
                System.out.println("Dashboard disconnected.");
            }
        }

        private void handleAdminBroadcast(String messageBody) {
            System.out.println("[ADMIN] Broadcasting message: " + messageBody);
            Message adminMessage = new Message(Message.MessageType.BROADCAST, "_admin_", null, messageBody, Priority.HIGH);
            // Use the server's main routing logic to send and store the message
            routeMessage(adminMessage);
        }

        private void handleAdminKick(String clientId) {
            System.out.println("[ADMIN] Kicking client: " + clientId);
            ClientHandler handler = clientHandlers.get(clientId);
            if (handler != null) {
                handler.disconnect(); // Gracefully disconnect the client
            } else {
                System.out.println(" -> Client '" + clientId + "' not found or already disconnected.");
            }
        }

        private String buildStateAsJson() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            // Stats
            json.append("\"stats\":{");
            json.append("\"onlineClients\":").append(clientHandlers.size()).append(",");
            json.append("\"pendingMessages\":").append(database.getPendingMessageCount());
            json.append("},");
            // Clients
            json.append("\"clients\":[");
            List<String> clientEntries = new ArrayList<>();
            clientHandlers.values().forEach(handler -> {
                clientEntries.add(String.format(
                        "{\"clientId\":\"%s\",\"lastSeen\":%d}",
                        handler.getClientId(), handler.getLastHeartbeatTime()
                ));
            });

            json.append(String.join(",", clientEntries));
            json.append("],");
            json.append("\"clientsWithPending\":[");
            List<String> pendingClientIds = database.getClientsWithPendingMessages();
            List<String> quotedIds = new ArrayList<>();
            pendingClientIds.forEach(id -> quotedIds.add("\"" + id + "\""));
            json.append(String.join(",", quotedIds));
            json.append("]");
            json.append("}");
            return json.toString();
        }

        private String buildPendingMessagesAsJson(String clientId) {
            List<Message> pending = database.getPendingMessagesForClient(clientId);
            StringBuilder json = new StringBuilder();
            json.append("[");
            List<String> msgEntries = new ArrayList<>();
            pending.forEach(msg -> {
                String safeBody = msg.getBody().replace("\"", "\\\"");
                msgEntries.add(String.format(
                        "{\"from\":\"%s\",\"priority\":\"%s\",\"body\":\"%s\"}",
                        msg.getFrom(), msg.getPriority(), safeBody
                ));
            });
            json.append(String.join(",", msgEntries));
            json.append("]");
            return json.toString();
        }
    }

    private class AdminConsole implements Runnable {
        @Override
        public void run() {
            try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
                while (!Thread.currentThread().isInterrupted()) {
                    String commandLine = consoleReader.readLine();
                    if (commandLine == null) break;
                    String[] parts = commandLine.trim().split("\\s+");
                    String command = parts[0].toLowerCase();
                    switch (command) {
                        case "/stats", "stats" -> handleStats();
                        case "/clients", "clients" -> handleListClients();
                        case "/pending", "pending" -> {
                            if (parts.length > 1) handleListPending(parts[1]);
                            else System.out.println("Usage: /pending <clientId>");
                        }
                        case "/tail", "tail" -> {
                            int count = (parts.length > 1) ? Integer.parseInt(parts[1]) : 10;
                            handleTailLogs(count);
                        }
                        case "/help", "help" -> printHelp();
                        default -> System.out.println("Unknown command. Type 'help' for a list of commands.");
                    }
                }
            } catch (IOException e) {
                System.err.println("Admin console encountered an error: " + e.getMessage());
            }
        }
        private void printHelp() {
            System.out.println("\n--- HelpHub Admin Console Commands ---");
            System.out.println(" /stats                  - Show server statistics.");
            System.out.println(" /clients                - List all currently connected clients.");
            System.out.println(" /pending <clientId>     - List pending messages for a specific client.");
            System.out.println(" /tail <n>               - Show the last <n> lines of the message log file.");
            System.out.println(" help                    - Show this help message.");
            System.out.println("--------------------------------------\n");
        }
        private void handleStats() {
            System.out.println("\n--- Server Statistics ---");
            System.out.printf(" Online Clients: %d%n", clientHandlers.size());
            System.out.printf(" Pending Messages: %d%n", database.getPendingMessageCount());
            System.out.printf(" Total Messages Stored: %d%n", database.getTotalMessageCount());
            System.out.println("-------------------------\n");
        }
        private void handleListClients() {
            System.out.println("\n--- Online Clients (" + clientHandlers.size() + ") ---");
            if (clientHandlers.isEmpty()) {
                System.out.println(" No clients are currently connected.");
            } else {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
                System.out.printf(" %-20s | %-15s%n", "Client ID", "Last Activity");
                System.out.println("----------------------------------------");
                clientHandlers.values().forEach(h -> System.out.printf(" %-20s | %-15s%n", h.getClientId(), formatter.format(Instant.ofEpochMilli(h.getLastHeartbeatTime()))));
            }
            System.out.println("----------------------------------------\n");
        }
        private void handleListPending(String clientId) {
            List<Message> pending = database.getPendingMessagesForClient(clientId);
            System.out.println("\n--- Pending Messages for '" + clientId + "' (" + pending.size() + ") ---");
            if (pending.isEmpty()) {
                System.out.println(" No pending messages for this client.");
            } else {
                pending.forEach(msg -> System.out.printf("  From: %-15s | Prio: %-6s | Body: %s%n", msg.getFrom(), msg.getPriority(), msg.getBody()));
            }
            System.out.println("--------------------------------------------------\n");
        }
        private void handleTailLogs(int count) {
            System.out.println("\n--- Last " + count + " Lines of " + LOG_FILE_PATH + " ---");
            File logFile = new File(LOG_FILE_PATH);
            if (!logFile.exists()) {
                System.out.println(" Log file does not exist yet.");
                return;
            }
            try {
                List<String> allLines = new ArrayList<>(java.nio.file.Files.readAllLines(logFile.toPath()));
                allLines.stream().skip(Math.max(0, allLines.size() - count)).forEach(System.out::println);
            } catch (IOException e) {
                System.err.println("Error reading log file: " + e.getMessage());
            }
            System.out.println("--------------------------------------------------\n");
        }
    }
}