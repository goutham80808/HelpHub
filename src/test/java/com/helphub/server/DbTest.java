package com.helphub.server;

import com.helphub.common.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DbTest {
    private Db database;

    @BeforeEach
    void setUp() {
        database = new Db("jdbc:sqlite::memory:");
    }

    @Test
    void testStoreAndRetrievePendingMessage() {
        Message msg = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "Test");
        database.storeMessage(msg);
        List<Message> pending = database.getPendingMessagesForClient("bravo");
        assertEquals(1, pending.size());
        assertEquals(msg.getId(), pending.get(0).getId());
    }

    @Test
    void testUpdateMessageStatus() {
        Message msg = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "Test");
        database.storeMessage(msg);
        database.updateMessageStatus(msg.getId(), "DELIVERED");
        List<Message> pending = database.getPendingMessagesForClient("bravo");
        assertTrue(pending.isEmpty());
    }
}