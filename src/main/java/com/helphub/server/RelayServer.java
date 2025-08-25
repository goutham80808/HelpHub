package com.helphub.server;

import com.helphub.common.Config;
import com.helphub.common.Message;
import com.helphub.common.Priority;
import com.helphub.server.web.WebSocketHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The main application class for the HelpHub Relay Server.
 * <p>
 * This server is the central hub of the HelpHub system. It is responsible for:
 * <ul>
 *   <li>Listening for secure connections from command-line (TCP) clients.</li>
 *   <li>Hosting a web server and listening for connections from web-based (WebSocket) clients.</li>
 *   <li>Listening for connections from the admin dashboard for monitoring and control.</li>
 *   <li>Routing messages between all connected clients.</li>
 *   <li>Persisting messages to a database for offline delivery.</li>
 *   <li>Broadcasting its presence on the network via mDNS for easy discovery.</li>
 * </ul>
 */
public class RelayServer {
    private static final Logger logger = LoggerFactory.getLogger(RelayServer.class);

    // --- Network Ports ---
    private static final int PORT = 5000;
    private static final int ADMIN_PORT = 5001;
    private static final int WEB_PORT = 8080;
    private static final String LOG_FILE_PATH = "logs/messages.log";

    // --- Server Components ---
    private final Map<String, ClientHandler> clientHandlers = new ConcurrentHashMap<>();
    private final Db database;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Server webServer;
    private JmDNS jmdns;

    public RelayServer() {
        this.database = new Db();
    }

    public static void main(String[] args) {
        new RelayServer().startServer();
    }

    /**
     * Initializes and starts all components of the RelayServer in the correct order.
     */
    public void startServer() {
        configureLogger();
        logger.info("HelpHub Relay Server starting...");

        startMDNSDiscovery();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        try {
            startWebServer();
        } catch (Exception e) {
            logger.error("FATAL: Could not start web server. Exiting.", e);
            System.exit(1);
        }

        setupSSL();
        startConnectionCleanupTask();
        new Thread(new AdminConsole()).start();
        logger.info("CLI Admin console started. Type 'help' for commands.");
        startAdminDataListener();

        logServerAddresses();
        startClientListener();
    }

    /**
     * Configures the SLF4J SimpleLogger for consistent, timestamped output.
     */
    private void configureLogger() {
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss:SSS");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.showLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out"); // Direct all logs to the console
    }

    /**
     * Starts the embedded Jetty web server and configures it to serve static files and WebSockets.
     */
    private void startWebServer() throws Exception {
        webServer = new Server(WEB_PORT);

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setResourceBase(Objects.requireNonNull(this.getClass().getClassLoader().getResource("webapp")).toExternalForm());
        resourceHandler.setWelcomeFiles(new String[]{"index.html"});

        ServletContextHandler servletContextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        servletContextHandler.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(servletContextHandler, (context, wsContainer) ->
                wsContainer.addMapping("/chat", (req, res) -> new WebSocketHandler())
        );
        WebSocketHandler.configure(this);

        webServer.setHandler(new HandlerList(resourceHandler, servletContextHandler));
        webServer.start();
        logger.info("Web server started on http://localhost:{}", WEB_PORT);
    }

    /**
     * Starts the mDNS service to broadcast the server's presence as 'helphub.local'.
     */
    private void startMDNSDiscovery() {
        try {
            jmdns = JmDNS.create(InetAddress.getLocalHost());
            ServiceInfo serviceInfo = ServiceInfo.create("_http._tcp.local.", "helphub", WEB_PORT, "HelpHub Emergency Chat");
            jmdns.registerService(serviceInfo);
            logger.info("mDNS service registered. Server may be discoverable at 'helphub.local'");
        } catch (IOException e) {
            logger.warn("Could not start mDNS service. Server only accessible via IP.", e);
        }
    }

    /**
     * Sets up the necessary system properties for SSL/TLS using the keystore file.
     * Fails fast if the required password is not provided as an environment variable.
     */
    private void setupSSL() {
        String keyStorePassword = System.getenv("KEYSTORE_PASSWORD");
        if (keyStorePassword == null || keyStorePassword.isBlank()) {
            logger.error("FATAL: KEYSTORE_PASSWORD environment variable not set. Exiting.");
            System.exit(1);
        }
        System.setProperty("javax.net.ssl.keyStore", "helphub.keystore");
        System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
    }

    /**
     * Starts the main server loop to listen for and accept secure connections from TCP clients.
     */
    private void startClientListener() {
        try {
            SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(PORT)) {
                logger.info("Server is listening for secure client connections on port {}", PORT);
                while (true) {
                    SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                    new Thread(new ClientHandler(clientSocket)).start();
                }
            }
        } catch (IOException e) {
            logger.error("Error in main server socket listener. The server may have stopped.", e);
        }
    }

    /**
     * Starts a background thread to listen for unencrypted connections from the admin dashboard.
     */
    private void startAdminDataListener() {
        new Thread(() -> {
            try (ServerSocket adminSocket = new ServerSocket(ADMIN_PORT)) {
                logger.info("Dashboard listener started on port {}", ADMIN_PORT);
                while (true) {
                    Socket dashboardConnection = adminSocket.accept();
                    new Thread(new AdminConnectionHandler(dashboardConnection)).start();
                }
            } catch (IOException e) {
                logger.error("FATAL: Could not start dashboard listener.", e);
            }
        }).start();
    }

    /**
     * Starts a scheduled task to periodically remove timed-out ("zombie") client connections.
     */
    private void startConnectionCleanupTask() {
        long timeout = Config.getInt("connection.timeout", 45000);
        scheduler.scheduleAtFixedRate(() -> {
            logger.debug("Running connection cleanup task...");
            long now = System.currentTimeMillis();
            clientHandlers.values().forEach(handler -> {
                if (now - handler.getLastHeartbeatTime() > timeout) {
                    logger.warn("Client '{}' timed out. Disconnecting.", handler.getClientId());
                    handler.disconnect();
                }
            });
        }, timeout, timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * The definitive, thread-safe method to check if a client ID is currently in use
     * by either a TCP client or a Web client.
     * @param clientId The ID to check.
     * @return true if the ID is already taken, false otherwise.
     */
    public synchronized boolean isClientIdTaken(String clientId) {
        boolean isTcpClient = clientHandlers.containsKey(clientId);
        boolean isWebClient = WebSocketHandler.isClientConnected(clientId);
        return isTcpClient || isWebClient;
    }

    /**
     * The intelligent routing hub for all messages in the system.
     * It logs, persists, and delivers messages to the correct recipients, whether they are TCP or Web clients,
     * handling both online and offline cases.
     * @param message The message to be routed.
     */
    public void routeMessageToAll(Message message) {
        String fromId = message.getFrom();
        String toId = message.getTo();

        logger.info("[MSG] [FROM:{}] -> [TO:{}]: {}", fromId, (toId == null ? "ALL" : toId), message.getBody());
        database.storeMessage(message);

        if (message.getType() == Message.MessageType.DIRECT) {
            boolean delivered = false;
            // Attempt delivery to an online TCP client
            ClientHandler tcpClient = clientHandlers.get(toId);
            if (tcpClient != null) {
                tcpClient.sendMessage(message.toJson());
                logger.info(" -> Delivered direct message to online TCP client: {}", toId);
                delivered = true;
            }
            // Attempt delivery to an online web client
            if (WebSocketHandler.isClientConnected(toId)) {
                WebSocketHandler.sendMessage(toId, message.toJson());
                logger.info(" -> Delivered direct message to online web client: {}", toId);
                delivered = true;
            }
            // If it wasn't delivered to anyone online, confirm it's queued for later
            if (!delivered) {
                logger.info(" -> Queued direct message for offline client: {}", toId);
            }
        } else { // BROADCAST messages
            // Send to all TCP clients (except the sender, if they are a TCP client)
            for (ClientHandler handler : clientHandlers.values()) {
                if (!handler.getClientId().equals(fromId)) {
                    handler.sendMessage(message.toJson());
                }
            }
            // Send to all Web clients (except the sender, if they are a web client)
            WebSocketHandler.broadcast(message.toJson(), fromId);
            logger.info(" -> Broadcast message sent to all online clients.");
        }
    }

    /**
     * A graceful shutdown hook to clean up resources like mDNS when the server is terminated.
     */
    public void shutdown() {
        logger.info("Shutting down HelpHub server...");
        if (jmdns != null) {
            jmdns.unregisterAllServices();
            try {
                jmdns.close();
                logger.info("mDNS service unregistered.");
            } catch (IOException e) {
                logger.error("Error closing mDNS service.", e);
            }
        }
        scheduler.shutdownNow();
    }

    /**
     * Prints a user-friendly banner to the console with easy-to-use connection addresses for end-users.
     */
    private void logServerAddresses() {
        logger.info("*********************************************************");
        logger.info("* HELP HUB IS LIVE");
        logger.info("*");
        logger.info("* Tell users to connect their phone to this Wi-Fi and");
        logger.info("* open their browser to ONE of the following addresses:");
        logger.info("*");
        logger.info("* Plan A (Easy): http://helphub.local:8080");
        logger.info("* Plan B (Fallback IPs):");
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                if (!netint.isUp() || netint.isLoopback() || netint.isVirtual()) continue;
                for (InetAddress inetAddress : Collections.list(netint.getInetAddresses())) {
                    if (inetAddress instanceof java.net.Inet4Address && inetAddress.isSiteLocalAddress()) {
                        logger.info("*     http://{}:{}", inetAddress.getHostAddress(), WEB_PORT);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Could not determine local IP addresses.", e);
        }
        logger.info("*");
        logger.info("*********************************************************");
    }

    // --------------------- INNER CLASSES ----------------------

    /** Handles a single, dedicated connection for a command-line (TCP) client. */
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
                if (clientId == null || clientId.trim().isEmpty() || isClientIdTaken(clientId)) {
                    logger.warn("Client disconnected: Invalid or duplicate ID provided ('{}').", clientId);
                    writer.println("ERROR: ID is invalid or already in use.");
                    return;
                }
                clientHandlers.put(this.clientId, this);
                database.updateClientLastSeen(clientId);
                logger.info("Client '{}' connected. Checking for pending messages...", this.clientId);
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
                        routeMessageToAll(message);
                    }
                }
            } catch (IOException e) {
                logger.warn("Connection lost for client '{}': {}", (clientId != null ? clientId : "UNKNOWN"), e.getMessage());
            } finally {
                disconnect();
            }
        }
        public void disconnect() {
            if (this.clientId != null) {
                clientHandlers.remove(this.clientId);
                logger.info("Client '{}' disconnected. Total clients: {}", this.clientId, clientHandlers.size());
            }
            try { if (!clientSocket.isClosed()) clientSocket.close(); } catch (IOException ignore) {}
        }
        private void flushPendingMessages() {
            List<Message> pending = database.getPendingMessagesForClient(clientId);
            if (!pending.isEmpty()) {
                logger.info("Sending {} pending messages to {}", pending.size(), clientId);
                for (Message msg : pending) {
                    sendMessage(msg.toJson());
                }
            }
        }
    }

    /** Handles a single connection for the JavaFX Admin Dashboard, serving JSON data and executing commands. */
    private class AdminConnectionHandler implements Runnable {
        private final Socket dashboardSocket;
        public AdminConnectionHandler(Socket socket) { this.dashboardSocket = socket; }
        @Override
        public void run() {
            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(dashboardSocket.getInputStream()));
                    PrintWriter writer = new PrintWriter(dashboardSocket.getOutputStream(), true)
            ) {
                String providedPassword = reader.readLine();
                String expectedPassword = System.getenv("ADMIN_PASSWORD");
                if (expectedPassword == null || expectedPassword.isEmpty() || !expectedPassword.equals(providedPassword)) {
                    logger.warn("Dashboard connection failed: Invalid admin password.");
                    writer.println("ERROR:AUTH_FAILED");
                    return;
                }
                String commandLine = reader.readLine();
                if (commandLine == null) return;
                String[] parts = commandLine.split("\\s+", 2);
                String command = parts[0];
                switch (command) {
                    case "GET_DATA" -> writer.println(buildStateAsJson());
                    case "GET_PENDING" -> {
                        if (parts.length > 1) writer.println(buildPendingMessagesAsJson(parts[1]));
                    }
                    case "ADMIN_BROADCAST" -> {
                        if (parts.length > 1) handleAdminBroadcast(parts[1]);
                    }
                    case "ADMIN_KICK" -> {
                        if (parts.length > 1) handleAdminKick(parts[1]);
                    }
                }
            } catch (IOException e) {
                logger.info("Dashboard disconnected.");
            }
        }
        private void handleAdminBroadcast(String messageBody) {
            logger.info("[ADMIN] Broadcasting message: {}", messageBody);
            Message adminMessage = new Message(Message.MessageType.BROADCAST, "_admin_", null, messageBody, Priority.HIGH);
            routeMessageToAll(adminMessage);
        }
        private void handleAdminKick(String clientId) {
            logger.info("[ADMIN] Kicking client: {}", clientId);
            ClientHandler handler = clientHandlers.get(clientId);
            if (handler != null) {
                handler.disconnect();
            } else {
                logger.info(" -> Client '{}' not found or already disconnected.", clientId);
            }
        }
        private String buildStateAsJson() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"stats\":{");
            json.append("\"onlineClients\":").append(clientHandlers.size() + WebSocketHandler.getConnectedClientIds().size()).append(",");
            json.append("\"pendingMessages\":").append(database.getPendingMessageCount());
            json.append("},");
            json.append("\"clients\":[");
            List<String> clientEntries = new ArrayList<>();
            clientHandlers.values().forEach(handler -> clientEntries.add(String.format("{\"clientId\":\"%s\",\"type\":\"TCP\",\"lastSeen\":%d}", handler.getClientId(), handler.getLastHeartbeatTime())));
            WebSocketHandler.getConnectedClientIds().forEach(clientId -> clientEntries.add(String.format("{\"clientId\":\"%s\",\"type\":\"Web\",\"lastSeen\":%d}", clientId, System.currentTimeMillis())));
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
                msgEntries.add(String.format("{\"from\":\"%s\",\"priority\":\"%s\",\"body\":\"%s\"}", msg.getFrom(), msg.getPriority(), safeBody));
            });
            json.append(String.join(",", msgEntries));
            json.append("]");
            return json.toString();
        }
    }

    /** Handles blocking input from the server's own console for CLI admin commands. */
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
                            else logger.info("Usage: /pending <clientId>");
                        }
                        case "/tail", "tail" -> {
                            try {
                                int count = (parts.length > 1) ? Integer.parseInt(parts[1]) : 10;
                                handleTailLogs(count);
                            } catch (NumberFormatException e) { logger.warn("Invalid number for tail command."); }
                        }
                        case "/help", "help" -> printHelp();
                        default -> logger.warn("Unknown command. Type 'help' for a list of commands.");
                    }
                }
            } catch (IOException e) {
                logger.error("Admin console encountered an error.", e);
            }
        }
        private void printHelp() {
            logger.info("\n--- HelpHub Admin Console Commands ---\n" +
                    " /stats                  - Show server statistics.\n" +
                    " /clients                - List all currently connected clients.\n" +
                    " /pending <clientId>     - List pending messages for a specific client.\n" +
                    " /tail <n>               - Show the last <n> lines of the message log file.\n" +
                    " help                    - Show this help message.\n" +
                    "--------------------------------------\n");
        }
        private void handleStats() {
            logger.info("\n--- Server Statistics ---\n Online Clients: {}\n Pending Messages: {}\n Total Messages Stored: {}\n-------------------------\n",
                    clientHandlers.size() + WebSocketHandler.getConnectedClientIds().size(),
                    database.getPendingMessageCount(),
                    database.getTotalMessageCount());
        }
        private void handleListClients() {
            logger.info("\n--- Online Clients ({}) ---", clientHandlers.size() + WebSocketHandler.getConnectedClientIds().size());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
            logger.info(String.format(" %-25s | %-8s | %-15s", "Client ID", "Type", "Last Activity"));
            clientHandlers.values().forEach(h -> logger.info(String.format(" %-25s | %-8s | %-15s", h.getClientId(), "TCP", formatter.format(Instant.ofEpochMilli(h.getLastHeartbeatTime())))));
            WebSocketHandler.getConnectedClientIds().forEach(id -> logger.info(String.format(" %-25s | %-8s | %-15s", id, "Web", "N/A")));
            logger.info("--------------------------------------------------\n");
        }
        private void handleListPending(String clientId) {
            List<Message> pending = database.getPendingMessagesForClient(clientId);
            logger.info("\n--- Pending Messages for {} ({} found) ---", clientId, pending.size());
            if (pending.isEmpty()) {
                logger.info(" No pending messages.");
            } else {
                pending.forEach(msg -> logger.info(" From: {} | Prio: {} | Body: {}", msg.getFrom(), msg.getPriority(), msg.getBody()));
            }
            logger.info("----------------------------------------\n");
        }
        private void handleTailLogs(int count) {
            // This method is intentionally left simple as a real-world implementation
            // would use a more robust log file management strategy.
            logger.info("The /tail command is a basic feature. For production, please view the log files directly or use a dedicated log aggregator.");
        }
    }
}