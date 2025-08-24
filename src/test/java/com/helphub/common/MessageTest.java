package com.helphub.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    @DisplayName("Message should serialize and deserialize correctly with NORMAL priority")
    void testSerializationAndDeserialization() {
        // FIX: Added Priority.NORMAL to the constructor call
        Message original = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "body", Priority.NORMAL);
        String json = original.toJson();
        Message deserialized = Message.fromJson(json);

        assertNotNull(deserialized, "Deserialized message should not be null");
        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getBody(), deserialized.getBody());
        // Also test that the priority was correctly deserialized
        assertEquals(Priority.NORMAL, deserialized.getPriority());
    }

    @Test
    @DisplayName("Message with quotes in body should serialize and deserialize correctly")
    void testSerializationWithQuotes() {
        String body = "He said, \"Hello!\"";
        // FIX: Added Priority.NORMAL to the constructor call
        Message original = new Message(Message.MessageType.BROADCAST, "charlie", null, body, Priority.NORMAL);
        String json = original.toJson();
        Message deserialized = Message.fromJson(json);

        assertNotNull(deserialized, "Deserialized message with quotes should not be null");
        assertEquals(body, deserialized.getBody());
    }

    @Test
    @DisplayName("Message should serialize and deserialize HIGH priority correctly")
    void testPrioritySerialization() {
        // 1. Create a message with HIGH priority
        Message original = new Message(Message.MessageType.BROADCAST, "admin", null, "SOS Message", Priority.HIGH);

        // 2. Serialize and check that the correct priority level (2) is in the JSON
        String json = original.toJson();
        assertTrue(json.contains("\"priority\":2"), "JSON string should contain the numeric level for HIGH priority");

        // 3. Deserialize and verify the Priority enum is correctly reconstructed
        Message deserialized = Message.fromJson(json);
        assertNotNull(deserialized, "High priority message should deserialize correctly");
        assertEquals(Priority.HIGH, deserialized.getPriority(), "Deserialized message priority should be HIGH");
    }

    @Test
    @DisplayName("fromJson should return null for malformed JSON")
    void testMalformedJson() {
        String malformedJson = "{\"id\":\"123\", \"type\":\"DIRECT\" \"from\":\"alpha\"}"; // Missing comma
        Message message = Message.fromJson(malformedJson);
        assertNull(message, "Message should be null for malformed JSON input");
    }
}