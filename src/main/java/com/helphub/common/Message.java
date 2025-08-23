package com.helphub.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * A Plain Old Java Object (POJO) representing a message in the HelpHub system.
 * Includes manual serialization/deserialization methods to/from a simple JSON format.
 */
public class Message {

    public enum MessageType {
        DIRECT, BROADCAST, STATUS, ACK, HEARTBEAT
    }

    private final String id;
    private final MessageType type;
    private final String from;
    private final String to; // Can be null for broadcast messages
    private final long timestamp;
    private final String body;

    // Constructor to create a new message
    public Message(MessageType type, String from, String to, String body) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.from = from;
        this.to = to;
        this.timestamp = System.currentTimeMillis();
        this.body = body;
    }

    // Private constructor for deserialization
    private Message(String id, MessageType type, String from, String to, long timestamp, String body) {
        this.id = id;
        this.type = type;
        this.from = from;
        this.to = to;
        this.timestamp = timestamp;
        this.body = body;
    }

    // Getters
    public String getId() { return id; }
    public MessageType getType() { return type; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public long getTimestamp() { return timestamp; }
    public String getBody() { return body; }


    /**
     * Serializes the Message object into a JSON string.
     * Note: This is a manual implementation without external libraries.
     * It escapes quotes in the message body.
     */
    public String toJson() {
        String safeBody = body.replace("\"", "\\\"");
        return String.format(
                "{\"id\":\"%s\",\"type\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"timestamp\":%d,\"body\":\"%s\"}",
                id, type, from, (to == null ? "null" : to), timestamp, safeBody
        );
    }

    /**
     * Deserializes a JSON string into a Message object.
     * Note: This is a manual parser and expects a specific, simple format.
     * @param json The JSON string to parse.
     * @return A new Message object, or null if parsing fails.
     */
    public static Message fromJson(String json) {
        try {
            // --- FIX: Replaced brittle regex with a more robust manual parser ---
            String id = extractValue(json, "id");
            String typeStr = extractValue(json, "type");
            String from = extractValue(json, "from");
            String to = extractValue(json, "to");
            String timestampStr = extractValue(json, "timestamp");
            String body = extractValue(json, "body");

            if (id == null || typeStr == null || from == null || body == null || timestampStr == null) {
                return null; // Invalid format
            }

            MessageType type = MessageType.valueOf(typeStr);
            long timestamp = Long.parseLong(timestampStr);
            if (to != null && to.equals("null")) {
                to = null;
            }

            return new Message(id, type, from, to, timestamp, body);

        } catch (Exception e) {
            System.err.println("Failed to parse JSON message: " + json + " | Error: " + e.getMessage());
            return null;
        }
    }

    /**
     * A helper method to extract a value for a given key from a simple JSON string.
     * Handles string values in quotes and numeric/null values.
     */
    private static String extractValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) {
            return null;
        }

        int valueStartIndex = keyIndex + searchKey.length();
        char firstChar = json.charAt(valueStartIndex);

        if (firstChar == '"') {
            // It's a string value, find the closing quote, ignoring escaped ones
            int endIndex = valueStartIndex + 1;
            while (endIndex < json.length()) {
                if (json.charAt(endIndex) == '"' && json.charAt(endIndex - 1) != '\\') {
                    break;
                }
                endIndex++;
            }
            return json.substring(valueStartIndex + 1, endIndex).replace("\\\"", "\"");
        } else {
            // It's a number or null
            int endIndex = valueStartIndex;
            while (endIndex < json.length() && json.charAt(endIndex) != ',' && json.charAt(endIndex) != '}') {
                endIndex++;
            }
            return json.substring(valueStartIndex, endIndex);
        }
    }

    /**
     * Creates a simple ACK message.
     * The body of the ACK contains the ID of the message being acknowledged.
     * @param fromClientId The client sending the ACK.
     * @param acknowledgedMessageId The ID of the message that was received.
     * @return A new Message object of type ACK.
     */
    public static Message createAck(String fromClientId, String acknowledgedMessageId) {
        return new Message(MessageType.ACK, fromClientId, null, acknowledgedMessageId);
    }

    /**
     * Creates a simple HEARTBEAT message.
     * The body is a simple ping payload.
     * @param fromClientId The client sending the heartbeat.
     * @return A new Message object of type HEARTBEAT.
     */
    public static Message createHeartbeat(String fromClientId) {
        return new Message(MessageType.HEARTBEAT, fromClientId, null, "ping");
    }

    /**
     * Creates a Message object from a java.sql.ResultSet.
     * @param rs The ResultSet from a database query.
     * @return A new Message object, or null if an error occurs.
     */
    public static Message fromResultSet(ResultSet rs) {
        try {
            String id = rs.getString("id");
            MessageType type = MessageType.valueOf(rs.getString("type"));
            String from = rs.getString("from_client");
            String to = rs.getString("to_client");
            long timestamp = rs.getLong("timestamp");
            String body = rs.getString("body");
            return new Message(id, type, from, to, timestamp, body);
        } catch (SQLException e) {
            System.err.println("Error creating Message from ResultSet: " + e.getMessage());
            return null;
        }
    }
}