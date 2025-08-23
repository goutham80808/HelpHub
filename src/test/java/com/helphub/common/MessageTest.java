package com.helphub.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    @DisplayName("Message should serialize and deserialize correctly")
    void testSerializationAndDeserialization() {
        // 1. Create a sample message
        Message originalMessage = new Message(
                Message.MessageType.DIRECT,
                "alpha",
                "bravo",
                "This is the message body."
        );

        // 2. Serialize it to JSON
        String json = originalMessage.toJson();
        assertNotNull(json, "JSON output should not be null");

        // 3. Deserialize it back into a Message object
        Message deserializedMessage = Message.fromJson(json);
        assertNotNull(deserializedMessage, "Deserialized message should not be null");

        // 4. Assert that all fields are the same
        assertEquals(originalMessage.getId(), deserializedMessage.getId());
        assertEquals(originalMessage.getType(), deserializedMessage.getType());
        assertEquals(originalMessage.getFrom(), deserializedMessage.getFrom());
        assertEquals(originalMessage.getTo(), deserializedMessage.getTo());
        assertEquals(originalMessage.getTimestamp(), deserializedMessage.getTimestamp());
        assertEquals(originalMessage.getBody(), deserializedMessage.getBody());
    }

    @Test
    @DisplayName("JSON serialization should handle quotes in the body")
    void testSerializationWithQuotes() {
        // 1. Create a message with quotes in the body
        String bodyWithQuotes = "He said, \"Hello, world!\" and it was important.";
        Message originalMessage = new Message(
                Message.MessageType.BROADCAST,
                "charlie",
                null,
                bodyWithQuotes
        );

        // 2. Serialize and deserialize
        String json = originalMessage.toJson();
        Message deserializedMessage = Message.fromJson(json);

        // 3. Assert the body content is perfectly preserved
        assertNotNull(deserializedMessage, "Deserialized message should not be null after handling quotes");
        assertEquals(bodyWithQuotes, deserializedMessage.getBody());
        System.out.println("Successfully tested serialization with quotes: " + json);
    }

    @Test
    @DisplayName("fromJson should return null for malformed JSON")
    void testMalformedJson() {
        String malformedJson = "{\"id\":\"123\", \"type\":\"DIRECT\" \"from\":\"alpha\"}"; // Missing comma
        Message message = Message.fromJson(malformedJson);
        assertNull(message, "Message should be null for malformed JSON input");
    }
}