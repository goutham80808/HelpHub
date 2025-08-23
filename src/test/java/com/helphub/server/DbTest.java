package com.helphub.server;

import com.helphub.common.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DbTest {

    private static final String IN_MEMORY_DB_URL = "jdbc:sqlite::memory:";
    private Db database;
    private Connection connection;

    @BeforeEach
    @DisplayName("Set up in-memory database before each test")
    void setUp() throws SQLException {
        // This creates a fresh, empty database in RAM for each test method
        connection = DriverManager.getConnection(IN_MEMORY_DB_URL);
        database = new Db(IN_MEMORY_DB_URL);
    }

    @AfterEach
    @DisplayName("Close database connection after each test")
    void tearDown() {
        // --- FIX: Call our new close method ---
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("Should store and retrieve a pending direct message")
    void testStoreAndRetrievePendingMessage() {
        // 1. Create a direct message
        Message directMessage = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "Test direct message");

        // 2. Store it
        database.storeMessage(directMessage);

        // 3. Retrieve pending messages for the recipient ('bravo')
        List<Message> pendingForBravo = database.getPendingMessagesForClient("bravo");
        assertEquals(1, pendingForBravo.size(), "Bravo should have one pending message");
        assertEquals(directMessage.getId(), pendingForBravo.get(0).getId());

        // 4. Ensure the sender ('alpha') has no pending messages
        List<Message> pendingForAlpha = database.getPendingMessagesForClient("alpha");
        assertTrue(pendingForAlpha.isEmpty(), "Alpha should have no pending messages for them");
    }

    @Test
    @DisplayName("Should update message status from PENDING to DELIVERED")
    void testUpdateMessageStatus() {
        // 1. Store a message, which is PENDING by default
        Message message = new Message(Message.MessageType.DIRECT, "alpha", "bravo", "Status update test");
        database.storeMessage(message);

        // 2. Update its status
        database.updateMessageStatus(message.getId(), "DELIVERED");

        // 3. Try to retrieve it as a PENDING message; the list should be empty
        List<Message> pendingMessages = database.getPendingMessagesForClient("bravo");
        assertTrue(pendingMessages.isEmpty(), "There should be no more PENDING messages after status update");
    }

    @Test
    @DisplayName("Broadcast messages should be retrieved by other clients")
    void testBroadcastMessageRetrieval() {
        // 1. Alpha sends a broadcast message
        Message broadcast = new Message(Message.MessageType.BROADCAST, "alpha", null, "This is a broadcast");
        database.storeMessage(broadcast);

        // 2. Bravo connects and should receive the pending broadcast
        List<Message> pendingForBravo = database.getPendingMessagesForClient("bravo");
        assertEquals(1, pendingForBravo.size(), "Bravo should receive the pending broadcast");

        // 3. Alpha (the sender) should not receive their own broadcast as a pending message
        List<Message> pendingForAlpha = database.getPendingMessagesForClient("alpha");
        assertTrue(pendingForAlpha.isEmpty(), "Sender should not get their own broadcast");
    }
}