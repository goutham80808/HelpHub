package com.helphub.server;

import com.helphub.common.Message;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.io.File;

public class Db {
    private static final String DB_PATH = "data/emergency.db";
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH;

    public Db() {
        new File("data").mkdirs(); // Ensure the 'data' directory exists
        initializeDatabase();
    }

    private synchronized void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            // Table for storing clients and their last seen time
            String clientsTableSql = "CREATE TABLE IF NOT EXISTS clients (" +
                    " id TEXT PRIMARY KEY," +
                    " last_seen INTEGER NOT NULL" +
                    ");";
            stmt.execute(clientsTableSql);

            // Table for storing all messages
            String messagesTableSql = "CREATE TABLE IF NOT EXISTS messages (" +
                    " id TEXT PRIMARY KEY," +
                    " from_client TEXT NOT NULL," +
                    " to_client TEXT," + // Can be null for broadcast
                    " type TEXT NOT NULL," +
                    " timestamp INTEGER NOT NULL," +
                    " body TEXT NOT NULL," +
                    " status TEXT NOT NULL" + // PENDING / DELIVERED
                    ");";
            stmt.execute(messagesTableSql);

            System.out.println("Database initialized successfully.");

        } catch (SQLException e) {
            System.err.println("Database initialization failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized void storeMessage(Message message) {
        String sql = "INSERT INTO messages(id, from_client, to_client, type, timestamp, body, status) VALUES(?,?,?,?,?,?,?)";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, message.getId());
            pstmt.setString(2, message.getFrom());
            pstmt.setString(3, message.getTo());
            pstmt.setString(4, message.getType().name());
            pstmt.setLong(5, message.getTimestamp());
            pstmt.setString(6, message.getBody());
            pstmt.setString(7, "PENDING"); // All new messages start as PENDING
            pstmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("Failed to store message: " + e.getMessage());
        }
    }

    public synchronized void updateMessageStatus(String messageId, String status) {
        String sql = "UPDATE messages SET status = ? WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, status);
            pstmt.setString(2, messageId);
            pstmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("Failed to update message status: " + e.getMessage());
        }
    }

    public synchronized List<Message> getPendingMessagesForClient(String clientId) {
        List<Message> pendingMessages = new ArrayList<>();
        // Select direct messages TO the client OR broadcast messages they haven't received
        // Note: A more robust broadcast implementation would track receipts per user.
        // For now, we assume any PENDING broadcast is new to a reconnecting user.
        String sql = "SELECT * FROM messages WHERE (to_client = ? AND status = 'PENDING') OR (type = 'BROADCAST' AND status = 'PENDING')";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, clientId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                Message message = Message.fromResultSet(rs);
                if (message != null) {
                    pendingMessages.add(message);
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to retrieve pending messages: " + e.getMessage());
        }
        return pendingMessages;
    }
}