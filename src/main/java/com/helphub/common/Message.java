// src/main/java/com/helphub/common/Message.java
package com.helphub.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class Message {

    public enum MessageType {
        DIRECT, BROADCAST, STATUS, ACK, HEARTBEAT
    }

    private final String id;
    private final MessageType type;
    private final String from;
    private final String to;
    private final long timestamp;
    private final String body;
    private final Priority priority;

    public Message(MessageType type, String from, String to, String body, Priority priority) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.from = from;
        this.to = to;
        this.timestamp = System.currentTimeMillis();
        this.body = body;
        this.priority = priority;
    }

    private Message(String id, MessageType type, String from, String to, long timestamp, String body, Priority priority) {
        this.id = id;
        this.type = type;
        this.from = from;
        this.to = to;
        this.timestamp = timestamp;
        this.body = body;
        this.priority = priority;
    }

    public String getId() { return id; }
    public MessageType getType() { return type; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public long getTimestamp() { return timestamp; }
    public String getBody() { return body; }
    public Priority getPriority() { return priority; }

    public String toJson() {
        String safeBody = body.replace("\\", "\\\\").replace("\"", "\\\"");
        return String.format(
                "{\"id\":\"%s\",\"type\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"timestamp\":%d,\"body\":\"%s\",\"priority\":%d}",
                id, type, from, (to == null ? "null" : to), timestamp, safeBody, priority.level
        );
    }

    public static Message fromJson(String json) {
        try {
            String id = extractValue(json, "id");
            String typeStr = extractValue(json, "type");
            String from = extractValue(json, "from");
            String to = extractValue(json, "to");
            String timestampStr = extractValue(json, "timestamp");
            String body = extractValue(json, "body");
            String priorityStr = extractValue(json, "priority");

            if (id == null || typeStr == null || from == null || body == null || timestampStr == null) return null;

            MessageType type = MessageType.valueOf(typeStr);
            long timestamp = Long.parseLong(timestampStr);
            if (to != null && to.equals("null")) to = null;
            Priority priority = (priorityStr != null) ? Priority.fromLevel(Integer.parseInt(priorityStr)) : Priority.NORMAL;

            return new Message(id, type, from, to, timestamp, body, priority);
        } catch (Exception e) {
            System.err.println("Failed to parse JSON message: " + json + " | Error: " + e.getMessage());
            return null;
        }
    }

    private static String extractValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int valueStartIndex = keyIndex + searchKey.length();
        char firstChar = json.charAt(valueStartIndex);

        if (firstChar == '"') {
            int endIndex = valueStartIndex + 1;
            while (endIndex < json.length()) {
                if (json.charAt(endIndex) == '"' && json.charAt(endIndex - 1) != '\\') break;
                endIndex++;
            }
            return json.substring(valueStartIndex + 1, endIndex).replace("\\\"", "\"").replace("\\\\", "\\");
        } else {
            int endIndex = valueStartIndex;
            while (endIndex < json.length() && json.charAt(endIndex) != ',' && json.charAt(endIndex) != '}') {
                endIndex++;
            }
            return json.substring(valueStartIndex, endIndex);
        }
    }

    public static Message createAck(String fromClientId, String acknowledgedMessageId) {
        return new Message(MessageType.ACK, fromClientId, null, acknowledgedMessageId, Priority.NORMAL);
    }

    public static Message createHeartbeat(String fromClientId) {
        return new Message(MessageType.HEARTBEAT, fromClientId, null, "ping", Priority.NORMAL);
    }

    public static Message fromResultSet(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        MessageType type = MessageType.valueOf(rs.getString("type"));
        String from = rs.getString("from_client");
        String to = rs.getString("to_client");
        long timestamp = rs.getLong("timestamp");
        String body = rs.getString("body");
        int priorityLevel = rs.getInt("priority");
        Priority priority = Priority.fromLevel(priorityLevel);
        return new Message(id, type, from, to, timestamp, body, priority);
    }
}