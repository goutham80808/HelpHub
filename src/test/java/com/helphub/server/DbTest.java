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
        // Using an in-memory database ensures tests are fast and isolated
        database = new Db("jdbc:sqlite::memory:");
    }

    @Test
    @DisplayName("Should store and retrieve a pending direct message")
    void testStoreAndRetrievePendingMessage() {
        // FIX: Added Priority.NORMAL to the constructor call
        Message msg = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "Test", Priority.NORMAL);
        database.storeMessage(msg);
        List<Message> pending = database.getPendingMessagesForClient("bravo");

        assertEquals(1, pending.size(), "Bravo should have one pending message");
        assertEquals(msg.getId(), pending.get(0).getId());
    }

    @Test
    @DisplayName("Should update message status from PENDING to DELIVERED")
    void testUpdateMessageStatus() {
        // FIX: Added Priority.NORMAL to the constructor call
        Message msg = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "Test", Priority.NORMAL);
        database.storeMessage(msg);
        database.updateMessageStatus(msg.getId(), "DELIVERED");

        List<Message> pending = database.getPendingMessagesForClient("bravo");
        assertTrue(pending.isEmpty(), "There should be no more PENDING messages after status update");
    }

    @Test
    @DisplayName("Should retrieve pending messages ordered by priority (HIGH > NORMAL > LOW)")
    void testPriorityOrderOfPendingMessages() {
        // 1. Create and store messages for 'charlie' out of order
        Message normalMsg = new Message(Message.MessageType.DIRECT, "alpha", "charlie", "Normal priority message", Priority.NORMAL);
        Message highMsg = new Message(Message.MessageType.DIRECT, "alpha", "charlie", "High priority message", Priority.HIGH);
        Message lowMsg = new Message(Message.MessageType.DIRECT, "alpha", "charlie", "Low priority message", Priority.LOW);

        database.storeMessage(normalMsg);
        database.storeMessage(highMsg);
        database.storeMessage(lowMsg);

        // 2. Retrieve the pending messages for 'charlie'
        List<Message> pending = database.getPendingMessagesForClient("charlie");

        // 3. Assert that the messages are returned in the correct priority order
        assertEquals(3, pending.size(), "There should be three pending messages");
        assertEquals(Priority.HIGH, pending.get(0).getPriority(), "The first message should be HIGH priority");
        assertEquals(Priority.NORMAL, pending.get(1).getPriority(), "The second message should be NORMAL priority");
        assertEquals(Priority.LOW, pending.get(2).getPriority(), "The third message should be LOW priority");
    }
}