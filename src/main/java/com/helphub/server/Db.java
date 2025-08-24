// src/main/java/com/helphub/server/Db.java
package com.helphub.server;

import com.helphub.common.Message;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class Db {
    private static final String DEFAULT_DB_PATH = "data/emergency.db";
    private final String dbUrl;
    private final Connection connection;

    public Db() {
        this("jdbc:sqlite:" + DEFAULT_DB_PATH);
        new File("data").mkdirs();
    }

    public Db(String dbUrl) {
        this.dbUrl = dbUrl;
        try {
            this.connection = DriverManager.getConnection(this.dbUrl);
            initializeDatabase();
        } catch (SQLException e) {
            System.err.println("FATAL: Failed to establish database connection: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void initializeDatabase() {
        try (Statement stmt = this.connection.createStatement()) {
            String clientsTableSql = "CREATE TABLE IF NOT EXISTS clients (id TEXT PRIMARY KEY, last_seen INTEGER NOT NULL);";
            stmt.execute(clientsTableSql);
            String messagesTableSql = "CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, " +
                    "from_client TEXT NOT NULL," +
                    " to_client TEXT, " +
                    "type TEXT NOT NULL," +
                    " timestamp INTEGER NOT NULL," +
                    " body TEXT NOT NULL," +
                    " priority INTEGER NOT NULL DEFAULT 1," +
                    " status TEXT NOT NULL);";
            stmt.execute(messagesTableSql);
            System.out.println("Database tables initialized successfully.");
        } catch (SQLException e) {
            System.err.println("Database table initialization failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized void storeMessage(Message message) {
        String sql = "INSERT INTO messages(id, from_client, to_client, type, timestamp, body, priority, status) VALUES(?,?,?,?,?,?,?,?)";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
            pstmt.setString(1, message.getId());
            pstmt.setString(2, message.getFrom());
            pstmt.setString(3, message.getTo());
            pstmt.setString(4, message.getType().name());
            pstmt.setLong(5, message.getTimestamp());
            pstmt.setString(6, message.getBody());
            pstmt.setInt(7, message.getPriority().level);
            pstmt.setString(8, "PENDING");
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to store message: " + e.getMessage());
        }
    }

    public synchronized void updateMessageStatus(String messageId, String status) {
        String sql = "UPDATE messages SET status = ? WHERE id = ?";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setString(2, messageId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to update message status: " + e.getMessage());
        }
    }

    public synchronized List<Message> getPendingMessagesForClient(String clientId) {
        List<Message> pendingMessages = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE (to_client = ? AND status = 'PENDING') OR " +
                "(type = 'BROADCAST' AND status = 'PENDING' AND from_client != ?) " +
                "ORDER BY priority DESC, timestamp ASC";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
            pstmt.setString(1, clientId);
            pstmt.setString(2, clientId);
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

    public synchronized void updateClientLastSeen(String clientId) {
        String sql = "INSERT INTO clients (id, last_seen) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET last_seen = excluded.last_seen;";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
            pstmt.setString(1, clientId);
            pstmt.setLong(2, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to update client last_seen: " + e.getMessage());
        }
    }

    public synchronized long getPendingMessageCount() {
        String sql = "SELECT COUNT(*) FROM messages WHERE status = 'PENDING'";
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            System.err.println("Failed to get pending message count: " + e.getMessage());
        }
        return 0;
    }

    public synchronized long getTotalMessageCount() {
        String sql = "SELECT COUNT(*) FROM messages";
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            System.err.println("Failed to get total message count: " + e.getMessage());
        }
        return 0;
    }
}