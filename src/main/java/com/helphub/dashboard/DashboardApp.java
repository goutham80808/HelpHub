// FILE: src/main/java/com/helphub/dashboard/DashboardApp.java
package com.helphub.dashboard;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DashboardApp extends Application {
    private final Label onlineCountLabel = new Label("Online Clients: (connecting...)");
    private final Label pendingCountLabel = new Label("Pending Messages: (connecting...)");
    private final TableView<ClientInfo> clientTable = new TableView<>();
    private final ListView<String> pendingMessagesView = new ListView<>();
    private final ListView<String> clientsWithPendingView = new ListView<>();
    private ScheduledExecutorService refreshExecutor;

    private final String adminPassword; // NEW: required for authentication

    public DashboardApp() {
        this.adminPassword = System.getProperty("ADMIN_PASSWORD");
    }

    @Override
    public void start(Stage primaryStage) {
        if (adminPassword == null || adminPassword.isEmpty()) {
            showError("Configuration Error",
                    "ADMIN_PASSWORD environment variable is not set.\nThe dashboard cannot connect to the server.");
            Platform.exit();
            return;
        }

        primaryStage.setTitle("HelpHub Server Dashboard");

        // --- Stats section ---
        VBox statsBox = new VBox(10, onlineCountLabel, pendingCountLabel);
        statsBox.setPadding(new Insets(10));

        setupClientTable();

        // --- Offline clients with pending ---
        VBox offlineLookupBox = new VBox(10);
        offlineLookupBox.setPadding(new Insets(10));
        Label offlineLabel = new Label("Offline Clients with Pending Messages (Click to view):");
        clientsWithPendingView.setPrefHeight(150);
        clientsWithPendingView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSelection, newSelection) -> {
                    if (newSelection != null) {
                        clientTable.getSelectionModel().clearSelection();
                        fetchPendingMessages(newSelection);
                    }
                });
        offlineLookupBox.getChildren().addAll(offlineLabel, clientsWithPendingView);

        // --- Pending messages view ---
        pendingMessagesView.setPlaceholder(
                new Label("Select a client or offline ID to see pending messages."));

        HBox mainContent = new HBox(10, clientTable, pendingMessagesView);
        mainContent.setPadding(new Insets(10));

        // --- Admin broadcast section ---
        HBox broadcastBox = new HBox(10);
        broadcastBox.setPadding(new Insets(10));
        TextField broadcastInput = new TextField();
        broadcastInput.setPromptText("Type a high-priority message to all clients...");
        HBox.setHgrow(broadcastInput, Priority.ALWAYS);
        Button sendButton = new Button("Send Broadcast");
        sendButton.setOnAction(e -> {
            String message = broadcastInput.getText();
            if (message != null && !message.trim().isEmpty()) {
                try {
                    sendAdminCommand("ADMIN_BROADCAST " + message);
                } catch (IOException ex) {
                    showError("Broadcast Failed", ex.getMessage());
                }
                broadcastInput.clear();
            }
        });
        broadcastBox.getChildren().addAll(new Label("Admin Broadcast:"), broadcastInput, sendButton);

        // --- Layout assembly ---
        BorderPane root = new BorderPane();
        VBox topContainer = new VBox(10, statsBox, broadcastBox);
        root.setTop(topContainer);
        root.setCenter(mainContent);
        root.setBottom(offlineLookupBox);

        Scene scene = new Scene(root, 900, 700);
        primaryStage.setScene(scene);
        primaryStage.show();

        startAutoRefresh();
    }

    private void setupClientTable() {
        TableColumn<ClientInfo, String> idColumn = new TableColumn<>("Client ID");
        idColumn.setCellValueFactory(new PropertyValueFactory<>("clientId"));
        idColumn.setPrefWidth(250);

        TableColumn<ClientInfo, String> lastSeenColumn = new TableColumn<>("Last Activity");
        lastSeenColumn.setCellValueFactory(new PropertyValueFactory<>("formattedLastSeen"));
        lastSeenColumn.setPrefWidth(150);

        clientTable.getColumns().addAll(idColumn, lastSeenColumn);
        clientTable.setPlaceholder(new Label("No clients online or data not loaded."));

        // click selection
        clientTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSelection, newSelection) -> {
                    if (newSelection != null) {
                        clientsWithPendingView.getSelectionModel().clearSelection();
                        fetchPendingMessages(newSelection.getClientId());
                    } else {
                        pendingMessagesView.getItems().clear();
                    }
                });

        // right-click context menu for admin kick
        ContextMenu contextMenu = new ContextMenu();
        MenuItem kickItem = new MenuItem("Force Disconnect Client");
        kickItem.setOnAction(e -> {
            ClientInfo selectedClient = clientTable.getSelectionModel().getSelectedItem();
            if (selectedClient != null) {
                confirmAndKickClient(selectedClient.getClientId());
            }
        });
        contextMenu.getItems().add(kickItem);
        clientTable.setContextMenu(contextMenu);
    }

    private void confirmAndKickClient(String clientId) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Disconnect");
        alert.setHeaderText("Disconnect client '" + clientId + "'?");
        alert.setContentText("This action cannot be undone.");
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                sendAdminCommand("ADMIN_KICK " + clientId);
            } catch (IOException ex) {
                showError("Kick Failed", ex.getMessage());
            }
        }
    }

    private void startAutoRefresh() {
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable);
            t.setDaemon(true);
            return t;
        });
        refreshExecutor.scheduleAtFixedRate(this::refreshData, 0, 5, TimeUnit.SECONDS);
    }

    private void refreshData() {
        new Thread(() -> {
            try {
                String jsonResponse = sendAdminCommand("GET_DATA");
                Platform.runLater(() -> parseAndDisplayData(jsonResponse));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    clientTable.getItems().clear();
                    clientsWithPendingView.getItems().clear();
                    onlineCountLabel.setText("Online Clients: (disconnected)");
                    pendingCountLabel.setText("Pending Messages: (disconnected)");
                    clientTable.setPlaceholder(new Label("Error connecting: " + e.getMessage()));
                });
            }
        }).start();
    }

    private void fetchPendingMessages(String clientId) {
        new Thread(() -> {
            try {
                String jsonResponse = sendAdminCommand("GET_PENDING " + clientId);
                Platform.runLater(() -> {
                    List<String> messages = new ArrayList<>();
                    Pattern msgPattern = Pattern.compile(
                            "\\{\"from\":\"(.*?)\",\"priority\":\"(.*?)\",\"body\":\"(.*?)\"\\}");
                    Matcher msgMatcher = msgPattern.matcher(jsonResponse);
                    while (msgMatcher.find()) {
                        messages.add(String.format("[%s] From %s: %s",
                                msgMatcher.group(2),
                                msgMatcher.group(1),
                                msgMatcher.group(3)));
                    }
                    pendingMessagesView.setItems(FXCollections.observableArrayList(messages));
                });
            } catch (Exception e) {
                Platform.runLater(() -> pendingMessagesView.setItems(
                        FXCollections.observableArrayList("Error loading messages.")));
            }
        }).start();
    }

    private String sendAdminCommand(String command) throws IOException {
        try (
                Socket socket = new Socket("localhost", 5001);
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        ) {
            // send password first
            writer.println(this.adminPassword);
            writer.println(command);

            String response = reader.readLine();
            if ("ERROR:AUTH_FAILED".equals(response)) {
                throw new IOException("Authentication failed. Check ADMIN_PASSWORD.");
            }
            return response;
        }
    }

    private void parseAndDisplayData(String json) {
        if (json == null) return;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        try {
            // stats
            Pattern statsPattern = Pattern.compile("\"stats\":\\{\"onlineClients\":(\\d+),\"pendingMessages\":(\\d+)\\}");
            Matcher statsMatcher = statsPattern.matcher(json);
            if (statsMatcher.find()) {
                onlineCountLabel.setText("Online Clients: " + statsMatcher.group(1));
                pendingCountLabel.setText("Pending Messages: " + statsMatcher.group(2));
            }

            // online clients
            List<ClientInfo> clients = new ArrayList<>();
            Pattern clientPattern = Pattern.compile("\\{\"clientId\":\"(.*?)\",\"lastSeen\":(\\d+)\\}");
            Matcher clientMatcher = clientPattern.matcher(json);
            while (clientMatcher.find()) {
                clients.add(new ClientInfo(
                        clientMatcher.group(1),
                        Long.parseLong(clientMatcher.group(2)),
                        formatter
                ));
            }
            clientTable.setItems(FXCollections.observableArrayList(clients));

            // offline clients with pending
            List<String> pendingClients = new ArrayList<>();
            Pattern pendingPattern = Pattern.compile("\"clientsWithPending\":\\[(.*?)\\]");
            Matcher pendingMatcher = pendingPattern.matcher(json);
            if (pendingMatcher.find()) {
                String idList = pendingMatcher.group(1);
                if (!idList.isEmpty()) {
                    for (String id : idList.split(",")) {
                        pendingClients.add(id.replace("\"", "").trim());
                    }
                }
            }
            clientsWithPendingView.setItems(FXCollections.observableArrayList(pendingClients));

        } catch (Exception e) {
            System.err.println("Failed to parse server data: " + e.getMessage());
        }
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
    }
}
