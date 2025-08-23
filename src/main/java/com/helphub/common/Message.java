// src/main/java/com/helphub/common/Message.java
package com.helphub.common;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Plain Old Java Object (POJO) representing a message in the HelpHub system.
 * Includes manual serialization/deserialization methods to/from a simple JSON format.
 */
public class Message {

    public enum MessageType {
        DIRECT, BROADCAST, STATUS
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
            // A simple regex to extract key-value pairs. It's not a full JSON parser.
            Pattern pattern = Pattern.compile("\"(.*?)\":\"?(.*?)\"?[,}]");
            Matcher matcher = pattern.matcher(json);

            String id = null, typeStr = null, from = null, to = null, body = null;
            long timestamp = 0;

            while (matcher.find()) {
                String key = matcher.group(1);
                String value = matcher.group(2);
                switch (key) {
                    case "id": id = value; break;
                    case "type": typeStr = value; break;
                    case "from": from = value; break;
                    case "to": to = value.equals("null") ? null : value; break;
                    case "timestamp": timestamp = Long.parseLong(value.replaceAll("[^0-9]", "")); break;
                    case "body": body = value.replace("\\\"", "\""); break;
                }
            }

            if (id == null || typeStr == null || from == null || body == null) {
                return null; // Invalid format
            }

            MessageType type = MessageType.valueOf(typeStr);
            return new Message(id, type, from, to, timestamp, body);

        } catch (Exception e) {
            System.err.println("Failed to parse JSON message: " + json);
            return null; // Return null on any parsing error
        }
    }
}