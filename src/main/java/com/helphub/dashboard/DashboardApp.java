// FILE: src/main/java/com/helphub/dashboard/DashboardApp.java
package com.helphub.dashboard;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DashboardApp extends Application {
    private final Label onlineCountLabel = new Label("Online Clients: (connecting...)");
    private final Label pendingCountLabel = new Label("Pending Messages: (connecting...)");
    private final TableView<ClientInfo> clientTable = new TableView<>();
    private final ListView<String> pendingMessagesView = new ListView<>();
    private ScheduledExecutorService refreshExecutor;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("HelpHub Server Dashboard");

        VBox statsBox = new VBox(10, onlineCountLabel, pendingCountLabel);
        statsBox.setPadding(new Insets(10));

        setupClientTable();

        pendingMessagesView.setPlaceholder(new Label("Select a client to see their pending messages."));

        HBox mainContent = new HBox(10, clientTable, pendingMessagesView);
        mainContent.setPadding(new Insets(10));

        BorderPane root = new BorderPane();
        root.setTop(statsBox);
        root.setCenter(mainContent);

        Scene scene = new Scene(root, 800, 600);
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

        clientTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSelection, newSelection) -> {
                    if (newSelection != null) {
                        fetchPendingMessages(newSelection.getClientId());
                    } else {
                        pendingMessagesView.getItems().clear();
                    }
                });
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
                    onlineCountLabel.setText("Online Clients: (disconnected)");
                    pendingCountLabel.setText("Pending Messages: (disconnected)");
                    clientTable.setPlaceholder(new Label("Error connecting to server: " + e.getMessage()));
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
                    Pattern msgPattern = Pattern.compile("\\{\"from\":\"(.*?)\",\"priority\":\"(.*?)\",\"body\":\"(.*?)\"\\}");
                    Matcher msgMatcher = msgPattern.matcher(jsonResponse);
                    while(msgMatcher.find()) {
                        messages.add(String.format("[%s] From %s: %s", msgMatcher.group(2), msgMatcher.group(1), msgMatcher.group(3)));
                    }
                    pendingMessagesView.setItems(FXCollections.observableArrayList(messages));
                });
            } catch (Exception e) {
                Platform.runLater(() -> pendingMessagesView.setItems(FXCollections.observableArrayList("Error loading messages.")));
            }
        }).start();
    }

    private String sendAdminCommand(String command) throws IOException {
        try (
                Socket socket = new Socket("localhost", 5001);
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        ) {
            writer.println(command);
            return reader.readLine();
        }
    }

    private void parseAndDisplayData(String json) {
        if (json == null) return;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        try {
            Pattern statsPattern = Pattern.compile("\"stats\":\\{\"onlineClients\":(\\d+),\"pendingMessages\":(\\d+)\\}");
            Matcher statsMatcher = statsPattern.matcher(json);
            if (statsMatcher.find()) {
                onlineCountLabel.setText("Online Clients: " + statsMatcher.group(1));
                pendingCountLabel.setText("Pending Messages: " + statsMatcher.group(2));
            }

            List<ClientInfo> clients = new ArrayList<>();
            Pattern clientPattern = Pattern.compile("\\{\"clientId\":\"(.*?)\",\"lastSeen\":(\\d+)\\}");
            Matcher clientMatcher = clientPattern.matcher(json);
            while (clientMatcher.find()) {
                clients.add(new ClientInfo(clientMatcher.group(1), Long.parseLong(clientMatcher.group(2)), formatter));
            }
            clientTable.setItems(FXCollections.observableArrayList(clients));
        } catch (Exception e) {
            System.err.println("Failed to parse server data: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
    }
}