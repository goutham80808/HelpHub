// src/test/java/com/helphub/server/DbTest.java
package com.helphub.server;

import com.helphub.common.Message;
import com.helphub.common.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DbTest {
    private Db database;

    @BeforeEach
    @DisplayName("Set up a fresh in-memory database for each test")
    void setUp() {
        database = new Db("jdbc:sqlite::memory:");
    }

    @Test
    @DisplayName("Should store and retrieve a pending direct message")
    void testStoreAndRetrievePendingMessage() {
        Message msg = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "Test", Priority.NORMAL);
        database.storeMessage(msg);
        List<Message> pending = database.getPendingMessagesForClient("bravo");

        assertEquals(1, pending.size());
        assertEquals(msg.getId(), pending.get(0).getId());
    }

    @Test
    @DisplayName("Should update message status from PENDING to DELIVERED")
    void testUpdateMessageStatus() {
        Message msg = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "Test", Priority.NORMAL);
        database.storeMessage(msg);
        database.updateMessageStatus(msg.getId(), "DELIVERED");

        List<Message> pending = database.getPendingMessagesForClient("bravo");
        assertTrue(pending.isEmpty());
    }

    @Test
    @DisplayName("Should retrieve pending messages ordered by priority (HIGH > NORMAL > LOW)")
    void testPriorityOrderOfPendingMessages() {
        Message normalMsg = new Message(Message.MessageType.DIRECT, "alpha", "charlie", "Normal", Priority.NORMAL);
        Message highMsg = new Message(Message.MessageType.DIRECT, "alpha", "charlie", "High", Priority.HIGH);
        Message lowMsg = new Message(Message.MessageType.DIRECT, "alpha", "charlie", "Low", Priority.LOW);

        database.storeMessage(normalMsg);
        database.storeMessage(highMsg);
        database.storeMessage(lowMsg);

        List<Message> pending = database.getPendingMessagesForClient("charlie");

        assertEquals(3, pending.size());
        assertEquals(Priority.HIGH, pending.get(0).getPriority());
        assertEquals(Priority.NORMAL, pending.get(1).getPriority());
        assertEquals(Priority.LOW, pending.get(2).getPriority());
    }
}