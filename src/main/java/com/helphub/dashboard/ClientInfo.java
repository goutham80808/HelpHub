// FILE: src/main/java/com/helphub/dashboard/ClientInfo.java
package com.helphub.dashboard;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class ClientInfo {
    private final StringProperty clientId;
    private final StringProperty formattedLastSeen;

    public ClientInfo(String clientId, long lastSeenTimestamp, DateTimeFormatter formatter) {
        this.clientId = new SimpleStringProperty(clientId);
        this.formattedLastSeen = new SimpleStringProperty(formatter.format(Instant.ofEpochMilli(lastSeenTimestamp)));
    }

    public String getClientId() { return clientId.get(); }
    public StringProperty clientIdProperty() { return clientId; }
    public String getFormattedLastSeen() { return formattedLastSeen.get(); }
    public StringProperty formattedLastSeenProperty() { return formattedLastSeen; }
}