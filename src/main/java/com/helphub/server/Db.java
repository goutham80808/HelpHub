package com.helphub.server;

import com.helphub.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages all database interactions for the HelpHub server using SQLite.
 * <p>
 * This class is designed to be thread-safe, with all public methods marked as {@code synchronized}.
 * It handles database initialization, schema migrations, and all CRUD (Create, Read, Update, Delete)
 * operations for messages and clients.
 */
public class Db {
    private static final Logger logger = LoggerFactory.getLogger(Db.class);
    private static final String DEFAULT_DB_PATH = "data/emergency.db";
    private final Connection connection;

    /** The target schema version for the current codebase. Migrations will run until the DB matches this version. */
    private static final int CURRENT_SCHEMA_VERSION = 2; // V1 was initial, V2 added the 'priority' column

    /**
     * Constructs a new Db instance using the default database file path.
     * Ensures the 'data' directory exists before connecting.
     */
    public Db() {
        new File("data").mkdirs();
        this.connection = connect("jdbc:sqlite:" + DEFAULT_DB_PATH);
        initializeDatabase();
    }

    /**
     * Constructs a new Db instance using a custom database URL.
     * This is primarily used for testing with an in-memory database (e.g., "jdbc:sqlite::memory:").
     * @param dbUrl The full JDBC URL for the database.
     */
    public Db(String dbUrl) {
        this.connection = connect(dbUrl);
        initializeDatabase();
    }

    /**
     * Establishes a connection to the SQLite database.
     * @param dbUrl The JDBC URL to connect to.
     * @return The active {@link Connection} object.
     * @throws RuntimeException if the database connection fails, as the application cannot proceed.
     */
    private Connection connect(String dbUrl) {
        try {
            return DriverManager.getConnection(dbUrl);
        } catch (SQLException e) {
            logger.error("FATAL: Failed to establish database connection to {}", dbUrl, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Ensures that all required database tables exist, creating them if necessary.
     * After ensuring tables exist, it triggers the schema migration process.
     */
    private void initializeDatabase() {
        try (Statement stmt = this.connection.createStatement()) {
            // Stores basic client info, primarily for tracking last seen time.
            stmt.execute("CREATE TABLE IF NOT EXISTS clients (id TEXT PRIMARY KEY, last_seen INTEGER NOT NULL);");

            // The main table for storing all message data.
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    " id TEXT PRIMARY KEY," +
                    " from_client TEXT NOT NULL," +
                    " to_client TEXT," + // Can be null for broadcast messages
                    " type TEXT NOT NULL," +
                    " timestamp INTEGER NOT NULL," +
                    " body TEXT NOT NULL," +
                    " status TEXT NOT NULL" +
                    ");");

            // A special table to track the current version of the database schema.
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY);");

            logger.info("Database tables checked/created successfully.");
            migrateSchema();
        } catch (SQLException e) {
            logger.error("Database table initialization failed", e);
        }
    }

    /**
     * A simple schema migration system. It checks the current version of the database
     * and applies any necessary {@code ALTER TABLE} commands sequentially to bring the
     * schema up to date with the current codebase.
     */
    private void migrateSchema() {
        int dbVersion = 0;
        try (Statement stmt = this.connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version");
            if (rs.next()) {
                dbVersion = rs.getInt("version");
            } else {
                // If no version is recorded, this is a brand new (Version 1) database.
                stmt.execute("INSERT INTO schema_version (version) VALUES (1)");
                dbVersion = 1;
            }
        } catch (SQLException e) {
            // This can happen on the very first run if the schema_version table itself doesn't exist yet.
            // We assume it's a pre-versioned database and proceed from version 0.
        }

        logger.info("Current database schema version: {}. Target version: {}", dbVersion, CURRENT_SCHEMA_VERSION);

        // --- Migration to Version 2 ---
        if (dbVersion < 2) {
            logger.info("Applying migration to version 2: Adding 'priority' column to messages table...");
            try (Statement stmt = this.connection.createStatement()) {
                stmt.execute("ALTER TABLE messages ADD COLUMN priority INTEGER NOT NULL DEFAULT 1;");
                stmt.execute("UPDATE schema_version SET version = 2");
                logger.info("Migration to version 2 successful.");
            } catch (SQLException e) {
                // This handles cases where the migration was partially run but failed to update the version number.
                if (e.getMessage() != null && e.getMessage().contains("duplicate column name")) {
                    logger.warn("Column 'priority' already exists. Forcing schema version update to 2.");
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute("UPDATE schema_version SET version = 2");
                    } catch (SQLException ex) {
                        logger.error("Failed to force schema version update.", ex);
                    }
                } else {
                    logger.error("Failed to migrate database to version 2", e);
                }
            }
        }
        // --- Future migrations would be added here as `if (dbVersion < 3) { ... }` ---
    }

    /**
     * Stores a message in the database with a PENDING status.
     * @param message The {@link Message} object to store.
     */
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
            logger.debug("Stored message {} from {}", message.getId(), message.getFrom());
        } catch (SQLException e) {
            logger.error("Failed to store message {}", message.getId(), e);
        }
    }

    /**
     * Updates the status of a message (e.g., from PENDING to DELIVERED).
     * @param messageId The ID of the message to update.
     * @param status The new status string.
     */
    public synchronized void updateMessageStatus(String messageId, String status) {
        String sql = "UPDATE messages SET status = ? WHERE id = ?";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setString(2, messageId);
            pstmt.executeUpdate();
            logger.debug("Updated message {} status -> {}", messageId, status);
        } catch (SQLException e) {
            logger.error("Failed to update message {} status -> {}", messageId, status, e);
        }
    }

    /**
     * Retrieves all pending messages for a given client, ordered by priority and then by timestamp.
     * This includes both direct messages to the client and broadcast messages they have not yet received.
     * @param clientId The ID of the client.
     * @return A {@link List} of {@link Message} objects.
     */
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
                pendingMessages.add(Message.fromResultSet(rs));
            }
            logger.debug("Fetched {} pending messages for client {}", pendingMessages.size(), clientId);
        } catch (SQLException e) {
            logger.error("Failed to retrieve pending messages for client {}", clientId, e);
        }
        return pendingMessages;
    }

    /**
     * Updates a client's 'last seen' timestamp in the database.
     * Uses an "UPSERT" (UPDATE or INSERT) operation to handle both existing and new clients.
     * @param clientId The ID of the client to update.
     */
    public synchronized void updateClientLastSeen(String clientId) {
        String sql = "INSERT INTO clients (id, last_seen) VALUES (?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET last_seen = excluded.last_seen;";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
            pstmt.setString(1, clientId);
            pstmt.setLong(2, System.currentTimeMillis());
            pstmt.executeUpdate();
            logger.debug("Updated last_seen for client {}", clientId);
        } catch (SQLException e) {
            logger.error("Failed to update last_seen for client {}", clientId, e);
        }
    }

    /**
     * Gets the total count of all messages currently in PENDING status.
     * @return The count of pending messages.
     */
    public synchronized long getPendingMessageCount() {
        String sql = "SELECT COUNT(*) FROM messages WHERE status = 'PENDING'";
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to get pending message count", e);
        }
        return 0;
    }

    /**
     * Gets the total count of all messages ever stored in the database.
     * @return The total message count.
     */
    public synchronized long getTotalMessageCount() {
        String sql = "SELECT COUNT(*) FROM messages";
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to get total message count", e);
        }
        return 0;
    }

    /**
     * Gets a list of unique client IDs that have pending messages directed to them.
     * @return A {@link List} of client ID strings.
     */
    public synchronized List<String> getClientsWithPendingMessages() {
        List<String> clientIds = new ArrayList<>();
        String sql = "SELECT DISTINCT to_client FROM messages WHERE status = 'PENDING' AND to_client IS NOT NULL";
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                clientIds.add(rs.getString("to_client"));
            }
            logger.debug("Found {} clients with pending messages", clientIds.size());
        } catch (SQLException e) {
            logger.error("Failed to get clients with pending messages", e);
        }
        return clientIds;
    }
}