package com.helphub.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTest {
    @Test
    void testSerializationAndDeserialization() {
        Message original = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "body");
        String json = original.toJson();
        Message deserialized = Message.fromJson(json);
        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getBody(), deserialized.getBody());
    }

    @Test
    void testSerializationWithQuotes() {
        String body = "He said, \"Hello!\"";
        Message original = new Message(Message.MessageType.BROADCAST, "charlie", null, body);
        String json = original.toJson();
        Message deserialized = Message.fromJson(json);
        assertEquals(body, deserialized.getBody());
    }
}