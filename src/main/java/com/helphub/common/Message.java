package com.helphub.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * An immutable Plain Old Java Object (POJO) representing a single unit of communication in the HelpHub system.
 * <p>
 * This class is the central data structure for all messages. It is designed to be thread-safe due to its
 * immutability (all fields are final). It includes a custom, lightweight JSON serialization/deserialization
 * implementation to avoid external library dependencies.
 */
public class Message {

    /** Defines the different types of messages that can be sent through the system. */
    public enum MessageType {
        /** A message directed to a single recipient. */
        DIRECT,
        /** A message sent to all connected clients. */
        BROADCAST,
        /** A message used for internal status updates, like client registration. */
        STATUS,
        /** A message sent by a client to acknowledge receipt of another message. */
        ACK,
        /** A keep-alive message sent by clients to signal they are still connected. */
        HEARTBEAT
    }

    private final String id;
    private final MessageType type;
    private final String from;
    private final String to;
    private final long timestamp;
    private final String body;
    private final Priority priority;
    // New: when the message was delivered to the recipient (set on ACK, 0 if not delivered)
    private final long deliveredTimestamp;

    /**
     * Creates a new message. This constructor is used when generating a new message from a client.
     * It automatically generates a unique ID and sets the current timestamp.
     *
     * @param type The {@link MessageType} of the message.
     * @param from The client ID of the sender.
     * @param to The client ID of the recipient (can be null for broadcasts).
     * @param body The text content of the message.
     * @param priority The {@link Priority} level of the message.
     */
    public Message(MessageType type, String from, String to, String body, Priority priority) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.from = from;
        this.to = to;
        this.timestamp = System.currentTimeMillis();
        this.body = body;
        this.priority = priority;
        this.deliveredTimestamp = 0L;
    }

    /**
     * A private constructor used internally for creating a Message object from existing data,
     * such as when deserializing from JSON or reading from the database.
     */
    private Message(String id, MessageType type, String from, String to, long timestamp, String body, Priority priority) {
        this(id, type, from, to, timestamp, body, priority, 0L);
    }

    // New constructor with deliveredTimestamp
    private Message(String id, MessageType type, String from, String to, long timestamp, String body, Priority priority, long deliveredTimestamp) {
        this.id = id;
        this.type = type;
        this.from = from;
        this.to = to;
        this.timestamp = timestamp;
        this.body = body;
        this.priority = priority;
        this.deliveredTimestamp = deliveredTimestamp;
    }

    // --- Standard Getters ---
    public String getId() { return id; }
    public MessageType getType() { return type; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public long getTimestamp() { return timestamp; }
    public String getBody() { return body; }
    public Priority getPriority() { return priority; }
    public long getDeliveredTimestamp() { return deliveredTimestamp; }

    /**
     * Serializes the Message object into a JSON string.
     * This custom implementation handles escaping of backslashes and quotes in the message body.
     * @return A single-line JSON representation of the message.
     */
    public String toJson() {
        String safeBody = body.replace("\\", "\\\\").replace("\"", "\\\"");
        // Only include deliveredTimestamp if it's set (nonzero)
        String deliveredPart = deliveredTimestamp > 0 ? String.format(",\"deliveredTimestamp\":%d", deliveredTimestamp) : "";
        return String.format(
                "{\"id\":\"%s\",\"type\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"timestamp\":%d,\"body\":\"%s\",\"priority\":%d%s}",
                id, type, from, (to == null ? "null" : to), timestamp, safeBody, priority.level, deliveredPart
        );
    }

    /**
     * Deserializes a JSON string into a Message object.
     * <p>
     * This robust parser is designed to handle messages from different client types.
     * It requires {@code type}, {@code from}, and {@code body} fields.
     * It treats other fields as optional, providing server-side defaults for {@code id}, {@code timestamp},
     * and {@code priority}. This allows lightweight clients (like the web client) to send simplified JSON.
     *
     * @param json The JSON string to parse.
     * @return A new Message object, or null if parsing fails or required fields are missing.
     */
    public static Message fromJson(String json) {
        try {
            // These fields are required from all clients.
            String typeStr = extractValue(json, "type");
            String from = extractValue(json, "from");
            String body = extractValue(json, "body");
            if (typeStr == null || from == null || body == null) return null; // Basic validation

            // These fields are optional; the server will provide defaults if they are missing.
            String id = extractValue(json, "id");
            String to = extractValue(json, "to");
            String timestampStr = extractValue(json, "timestamp");
            String priorityStr = extractValue(json, "priority");
            String deliveredTimestampStr = extractValue(json, "deliveredTimestamp");

            // Provide server-side defaults for missing optional fields.
            String finalId = (id != null) ? id : UUID.randomUUID().toString();
            long finalTimestamp = (timestampStr != null) ? Long.parseLong(timestampStr) : System.currentTimeMillis();
            Priority finalPriority = (priorityStr != null) ? Priority.fromLevel(Integer.parseInt(priorityStr)) : Priority.NORMAL;
            long finalDeliveredTimestamp = (deliveredTimestampStr != null) ? Long.parseLong(deliveredTimestampStr) : 0L;
            if (to != null && to.equals("null")) to = null;
            // calling the private internal constructor
            return new Message(finalId, MessageType.valueOf(typeStr), from, to, finalTimestamp, body, finalPriority, finalDeliveredTimestamp);
        } catch (Exception e) {
            // In a production system, this would use the SLF4J logger.
            System.err.println("Failed to parse JSON message: " + json + " | Error: " + e.getMessage());
            return null;
        }
    }

    /**
     * A private helper method to extract a value for a given key from a simple JSON string.
     * It is not a full JSON parser but is sufficient for our simple, flat structure.
     * @param json The JSON string.
     * @param key The key whose value to extract.
     * @return The extracted value as a string, or null if the key is not found.
     */
    private static String extractValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int valueStartIndex = keyIndex + searchKey.length();
        char firstChar = json.charAt(valueStartIndex);

        if (firstChar == '"') { // It's a string value
            int endIndex = valueStartIndex + 1;
            while (endIndex < json.length()) {
                if (json.charAt(endIndex) == '"' && json.charAt(endIndex - 1) != '\\') break;
                endIndex++;
            }
            return json.substring(valueStartIndex + 1, endIndex).replace("\\\"", "\"").replace("\\\\", "\\");
        } else { // It's a number or null
            int endIndex = valueStartIndex;
            while (endIndex < json.length() && json.charAt(endIndex) != ',' && json.charAt(endIndex) != '}') {
                endIndex++;
            }
            return json.substring(valueStartIndex, endIndex);
        }
    }

    /**
     * A factory method for creating a standard ACK message.
     * @param fromClientId The ID of the client sending the acknowledgment.
     * @param acknowledgedMessageId The ID of the message being acknowledged.
     * @return A new {@code Message} object of type {@code ACK}.
     */
    public static Message createAck(String fromClientId, String acknowledgedMessageId) {
        return new Message(MessageType.ACK, fromClientId, null, acknowledgedMessageId, Priority.NORMAL);
    }

    /**
     * A factory method for creating a standard HEARTBEAT message.
     * @param fromClientId The ID of the client sending the heartbeat.
     * @return A new {@code Message} object of type {@code HEARTBEAT}.
     */
    public static Message createHeartbeat(String fromClientId) {
        return new Message(MessageType.HEARTBEAT, fromClientId, null, "ping", Priority.NORMAL);
    }

    /**
     * A factory method for creating a Message object directly from a database {@link ResultSet}.
     * @param rs The ResultSet from a database query, positioned at a valid row.
     * @return A new {@code Message} object.
     * @throws SQLException if a column is not found or a data type error occurs.
     */
    public static Message fromResultSet(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        MessageType type = MessageType.valueOf(rs.getString("type"));
        String from = rs.getString("from_client");
        String to = rs.getString("to_client");
        long timestamp = rs.getLong("timestamp");
        String body = rs.getString("body");
        int priorityLevel = rs.getInt("priority");
        Priority priority = Priority.fromLevel(priorityLevel);
        long deliveredTimestamp = 0L;
        try {
            deliveredTimestamp = rs.getLong("delivered_timestamp");
        } catch (SQLException e) {
            // Column may not exist yet (for backward compatibility)
        }
        return new Message(id, type, from, to, timestamp, body, priority, deliveredTimestamp);
    }
}