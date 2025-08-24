// src/test/java/com/helphub/common/MessageTest.java
package com.helphub.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    @DisplayName("Message should serialize and deserialize correctly with NORMAL priority")
    void testSerializationAndDeserialization() {
        Message original = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "body", Priority.NORMAL);
        String json = original.toJson();
        Message deserialized = Message.fromJson(json);

        assertNotNull(deserialized);
        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getBody(), deserialized.getBody());
        assertEquals(Priority.NORMAL, deserialized.getPriority());
    }

    @Test
    @DisplayName("Message with quotes in body should serialize and deserialize correctly")
    void testSerializationWithQuotes() {
        String body = "He said, \"Hello!\"";
        Message original = new Message(Message.MessageType.BROADCAST, "charlie", null, body, Priority.NORMAL);
        String json = original.toJson();
        Message deserialized = Message.fromJson(json);

        assertNotNull(deserialized);
        assertEquals(body, deserialized.getBody());
    }

    @Test
    @DisplayName("Message should serialize and deserialize HIGH priority correctly")
    void testPrioritySerialization() {
        Message original = new Message(Message.MessageType.BROADCAST, "admin", null, "SOS Message", Priority.HIGH);
        String json = original.toJson();
        assertTrue(json.contains("\"priority\":2"));

        Message deserialized = Message.fromJson(json);
        assertNotNull(deserialized);
        assertEquals(Priority.HIGH, deserialized.getPriority());
    }

    @Test
    @DisplayName("fromJson should return null for malformed JSON")
    void testMalformedJson() {
        String malformedJson = "{\"id\":\"123\", \"type\":\"DIRECT\" \"from\":\"alpha\"}";
        Message message = Message.fromJson(malformedJson);
        assertNull(message);
    }
}