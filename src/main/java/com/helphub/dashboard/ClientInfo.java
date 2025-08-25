package com.helphub.dashboard;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import javafx.scene.control.TableView;

/**
 * A JavaFX data model class representing a single row in the client information table.
 * <p>
 * This class follows the JavaFX Property pattern, using {@link StringProperty} for its fields.
 * This allows the JavaFX {@link TableView} to automatically observe and update the UI
 * when the data in an instance of this class changes. It encapsulates all the information
 * needed to display a connected client in the dashboard.
 */
public class ClientInfo {
    /** The unique identifier of the client. */
    private final StringProperty clientId;

    /** The type of the client (e.g., "TCP" or "Web"). */
    private final StringProperty type;

    /** The pre-formatted timestamp of the client's last known activity. */
    private final StringProperty formattedLastSeen;

    /**
     * Constructs a new ClientInfo object.
     *
     * @param clientId The client's unique ID.
     * @param type The client's connection type (e.g., "TCP").
     * @param lastSeenTimestamp The raw epoch timestamp of the last activity.
     * @param formatter A {@link DateTimeFormatter} used to create a user-friendly time string.
     */
    public ClientInfo(String clientId, String type, long lastSeenTimestamp, DateTimeFormatter formatter) {
        this.clientId = new SimpleStringProperty(clientId);
        this.type = new SimpleStringProperty(type);
        this.formattedLastSeen = new SimpleStringProperty(formatter.format(Instant.ofEpochMilli(lastSeenTimestamp)));
    }

    // --- JavaFX Property Accessors ---
    // The pattern is:
    // 1. A standard getter: getClientId()
    // 2. A property getter: clientIdProperty() which returns the StringProperty object.
    // This allows the TableView to bind to the property for automatic updates.

    public String getClientId() {
        return clientId.get();
    }

    public StringProperty clientIdProperty() {
        return clientId;
    }

    public String getType() {
        return type.get();
    }

    public StringProperty typeProperty() {
        return type;
    }

    public String getFormattedLastSeen() {
        return formattedLastSeen.get();
    }

    public StringProperty formattedLastSeenProperty() {
        return formattedLastSeen;
    }
}