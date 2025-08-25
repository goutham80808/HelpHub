package com.helphub.server.web;

import com.helphub.common.Message;
import com.helphub.server.RelayServer;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all WebSocket connections for the HelpHub server.
 * <p>
 * An instance of this class is created by Jetty for each new web client that connects.
 * It manages the lifecycle of a single WebSocket session, including connection, messages,
 * closure, and errors. It acts as the primary bridge between the web-based clients and the
 * core {@link RelayServer} logic.
 */
@WebSocket
public class WebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

    /** A thread-safe, static map of all currently connected web clients, mapping their unique ID to their session. */
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** A static reference to the main RelayServer instance, used for message routing and ID validation. */
    private static RelayServer relayServer;

    /**
     * Injects the main RelayServer instance into this handler. This must be called at startup.
     * @param server The running instance of the RelayServer.
     */
    public static void configure(RelayServer server) {
        relayServer = server;
    }

    /**
     * Checks if a web client with the given ID is currently connected.
     * @param clientId The ID to check.
     * @return {@code true} if the client is connected, {@code false} otherwise.
     */
    public static boolean isClientConnected(String clientId) {
        return sessions.containsKey(clientId);
    }

    /**
     * Sends a message to a specific, single web client.
     * @param clientId The ID of the target client.
     * @param messageJson The JSON message payload to send.
     */
    public static void sendMessage(String clientId, String messageJson) {
        Session session = sessions.get(clientId);
        if (session != null && session.isOpen()) {
            try {
                session.getRemote().sendString(messageJson);
            } catch (IOException e) {
                logger.warn("Error sending direct message to web client '{}': {}", clientId, e.getMessage());
            }
        }
    }

    /**
     * Broadcasts a message to all connected web clients, optionally excluding the original sender.
     * @param messageJson The JSON message payload to send.
     * @param excludeClientId The ID of the client to exclude from the broadcast (can be null).
     */
    public static void broadcast(String messageJson, String excludeClientId) {
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (!entry.getKey().equals(excludeClientId)) {
                sendMessage(entry.getKey(), messageJson);
            }
        }
    }

    /** Allows other parts of the server to get a list of connected web client IDs for monitoring. */
    public static java.util.Set<String> getConnectedClientIds() {
        return sessions.keySet();
    }

    /** The unique ID of the client associated with this specific handler instance. */
    private String clientId;

    /**
     * Called by Jetty when a new WebSocket connection is established.
     * @param session The newly opened session.
     */
    @OnWebSocketConnect
    public void onConnect(Session session) {
        logger.info("New web client connecting: {}", session.getRemoteAddress());
        // The client is expected to identify itself in its first message.
    }

    /**
     * Called by Jetty when a message is received from the WebSocket client.
     * This method handles both initial client registration and subsequent message routing.
     * @param session The session that sent the message.
     * @param jsonText The message payload from the client.
     */
    @OnWebSocketMessage
    public void onMessage(Session session, String jsonText) {
        Message message = Message.fromJson(jsonText);
        if (message == null) {
            logger.warn("Received malformed JSON from web client: {}", jsonText);
            return;
        }

        // The first message from a client is treated as its registration.
        if (this.clientId == null) {
            String requestedId = message.getFrom();
            if (relayServer.isClientIdTaken(requestedId)) {
                logger.warn("Web client registration failed: Duplicate ID ('{}')", requestedId);
                try {
                    session.getRemote().sendString("{\"type\":\"ERROR\",\"body\":\"ID_TAKEN\"}");
                    session.close();
                } catch (IOException e) { /* Client is already gone, ignore */ }
                return;
            }
            this.clientId = requestedId;
            sessions.put(this.clientId, session);
            logger.info("Web client '{}' registered successfully.", this.clientId);

            // If the registration message was a real message (not just a STATUS ping), route it.
            if (message.getType() != Message.MessageType.STATUS) {
                relayServer.routeMessageToAll(message);
            }
        } else {
            // This is a regular message from an already registered client.
            relayServer.routeMessageToAll(message);
        }
    }

    /**
     * Called by Jetty when a WebSocket connection is closed.
     * @param session The session that was closed.
     * @param statusCode The closing status code.
     * @param reason A description of why the session was closed.
     */
    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        if (this.clientId != null) {
            logger.info("Web client '{}' disconnected. Reason: {} (code={})", this.clientId, reason, statusCode);
            sessions.remove(this.clientId);
        }
    }

    /**
     * Called by Jetty when a WebSocket error occurs.
     * @param session The session where the error occurred (can be null).
     * @param error The throwable error.
     */
    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        // Provide a more informative error log, handling cases where the session might be null.
        String clientIdentifier = (this.clientId != null) ? this.clientId :
                (session != null ? session.getRemoteAddress().toString() : "UNKNOWN");
        logger.error("WebSocket error for client '{}': {}", clientIdentifier, error.getMessage());
    }
}