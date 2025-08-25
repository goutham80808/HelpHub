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
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The main entry point for the HelpHub JavaFX Admin Dashboard.
 * <p>
 * This application provides a graphical user interface for monitoring and controlling the RelayServer.
 * It connects to the server's dedicated admin port, fetches data periodically, and provides
 * administrative actions like broadcasting messages and disconnecting clients.
 */
public class DashboardApp extends Application {

    // --- UI Components ---
    private final Label onlineCountLabel = new Label("Online Clients: (connecting...)");
    private final Label pendingCountLabel = new Label("Pending Messages: (connecting...)");
    private final TableView<ClientInfo> clientTable = new TableView<>();
    private final ListView<String> pendingMessagesView = new ListView<>();
    private final ListView<String> clientsWithPendingView = new ListView<>();

    /** A background thread executor for periodically refreshing data from the server. */
    private ScheduledExecutorService refreshExecutor;

    /** The admin password required to authenticate with the server's admin port. */
    private final String adminPassword;

    /**
     * Constructs the DashboardApp, reading the required admin password from system properties.
     * This password must be passed via a -D flag by the Maven javafx:run command.
     */
    public DashboardApp() {
        this.adminPassword = System.getProperty("ADMIN_PASSWORD");
    }

    /**
     * The main entry point for all JavaFX applications.
     * This method builds the UI, sets up event handlers, and starts the auto-refresh timer.
     *
     * @param primaryStage The primary stage for this application, onto which the scene is set.
     */
    @Override
    public void start(Stage primaryStage) {
        // Fail-fast: If the password wasn't provided, show an error and exit immediately.
        if (adminPassword == null || adminPassword.isEmpty()) {
            showError("Configuration Error", "ADMIN_PASSWORD was not provided.\nRun with: mvn javafx:run -Dadmin.password=YourPassword");
            Platform.exit();
            return;
        }

        primaryStage.setTitle("HelpHub Server Dashboard");

        // --- UI Layout and Assembly ---

        VBox statsBox = new VBox(10, onlineCountLabel, pendingCountLabel);
        statsBox.setPadding(new Insets(10));

        setupClientTable();

        VBox offlineLookupBox = new VBox(10);
        offlineLookupBox.setPadding(new Insets(10));
        offlineLookupBox.getChildren().addAll(new Label("Offline Clients with Pending Messages (Click to view):"), clientsWithPendingView);
        clientsWithPendingView.setPrefHeight(150);
        clientsWithPendingView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSelection, newSelection) -> {
                    if (newSelection != null) {
                        clientTable.getSelectionModel().clearSelection(); // Ensure only one list is selected
                        fetchPendingMessages(newSelection);
                    }
                });

        pendingMessagesView.setPlaceholder(new Label("Select a client to see their pending messages."));

        HBox mainContent = new HBox(10, clientTable, pendingMessagesView);
        mainContent.setPadding(new Insets(10));

        HBox broadcastBox = new HBox(10);
        broadcastBox.setPadding(new Insets(10));
        TextField broadcastInput = new TextField();
        broadcastInput.setPromptText("Type a high-priority message to all clients...");
        HBox.setHgrow(broadcastInput, Priority.ALWAYS);
        Button sendButton = new Button("Send Broadcast");
        sendButton.setOnAction(e -> {
            String message = broadcastInput.getText();
            if (message != null && !message.trim().isEmpty()) {
                sendAdminCommandInBackground("ADMIN_BROADCAST " + message);
                broadcastInput.clear();
            }
        });
        broadcastBox.getChildren().addAll(new Label("Admin Broadcast:"), broadcastInput, sendButton);

        BorderPane root = new BorderPane();
        root.setTop(new VBox(10, statsBox, broadcastBox));
        root.setCenter(mainContent);
        root.setBottom(offlineLookupBox);

        Scene scene = new Scene(root, 900, 700);
        primaryStage.setScene(scene);
        primaryStage.show();

        startAutoRefresh();
    }

    /**
     * Configures the columns, context menu, and selection behavior for the main client table.
     */
    private void setupClientTable() {
        TableColumn<ClientInfo, String> idColumn = new TableColumn<>("Client ID");
        idColumn.setCellValueFactory(new PropertyValueFactory<>("clientId"));
        idColumn.setPrefWidth(250);

        TableColumn<ClientInfo, String> typeColumn = new TableColumn<>("Type");
        typeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeColumn.setPrefWidth(80);

        TableColumn<ClientInfo, String> lastSeenColumn = new TableColumn<>("Last Activity");
        lastSeenColumn.setCellValueFactory(new PropertyValueFactory<>("formattedLastSeen"));
        lastSeenColumn.setPrefWidth(150);

        // --- FIX: Correctly set all columns for the table ---
        clientTable.getColumns().setAll(idColumn, typeColumn, lastSeenColumn);
        clientTable.setPlaceholder(new Label("No clients online or data not loaded."));

        // Add a listener to fetch pending messages when a client is clicked.
        clientTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSelection, newSelection) -> {
                    if (newSelection != null) {
                        clientsWithPendingView.getSelectionModel().clearSelection(); // Ensure only one list is selected
                        fetchPendingMessages(newSelection.getClientId());
                    } else {
                        pendingMessagesView.getItems().clear();
                    }
                });

        // Add a right-click context menu for admin actions (e.g., kicking a client).
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

    /**
     * Displays a confirmation dialog before kicking a client.
     * @param clientId The ID of the client to disconnect.
     */
    private void confirmAndKickClient(String clientId) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Disconnect");
        alert.setHeaderText("Disconnect client '" + clientId + "'?");
        alert.setContentText("This will forcefully close their connection.");
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            sendAdminCommandInBackground("ADMIN_KICK " + clientId);
        }
    }

    /**
     * Starts a background task to refresh the dashboard data every 5 seconds.
     */
    private void startAutoRefresh() {
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable);
            t.setDaemon(true); // Allows the application to exit without waiting for this thread
            return t;
        });
        refreshExecutor.scheduleAtFixedRate(this::refreshData, 0, 5, TimeUnit.SECONDS);
    }

    /**
     * The main data refresh task. It fetches the server state and updates the UI.
     * All network I/O is performed on a background thread.
     */
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

    /**
     * Fetches the list of pending messages for a specific client ID.
     * All network I/O is performed on a background thread.
     * @param clientId The ID of the client to look up.
     */
    private void fetchPendingMessages(String clientId) {
        new Thread(() -> {
            try {
                String jsonResponse = sendAdminCommand("GET_PENDING " + clientId);
                Platform.runLater(() -> {
                    List<String> messages = new ArrayList<>();
                    Pattern msgPattern = Pattern.compile("\\{\"from\":\"(.*?)\",\"priority\":\"(.*?)\",\"body\":\"(.*?)\"\\}");
                    Matcher msgMatcher = msgPattern.matcher(jsonResponse);
                    while (msgMatcher.find()) {
                        messages.add(String.format("[%s] From %s: %s", msgMatcher.group(2), msgMatcher.group(1), msgMatcher.group(3)));
                    }
                    pendingMessagesView.setItems(FXCollections.observableArrayList(messages));
                });
            } catch (Exception e) {
                Platform.runLater(() -> pendingMessagesView.setItems(FXCollections.observableArrayList("Error loading messages.")));
            }
        }).start();
    }

    /**
     * A fire-and-forget method to send admin commands that don't require a response.
     * @param command The full command string to send.
     */
    private void sendAdminCommandInBackground(String command) {
        new Thread(() -> {
            try {
                sendAdminCommand(command);
            } catch (IOException e) {
                Platform.runLater(() -> showError("Admin Command Failed", e.getMessage()));
            }
        }).start();
    }

    /**
     * The core networking method for communicating with the server's admin port.
     * It handles authentication and sends a single command.
     * @param command The command to send.
     * @return The server's single-line response.
     * @throws IOException if the connection fails or authentication is rejected.
     */
    private String sendAdminCommand(String command) throws IOException {
        try (
                Socket socket = new Socket("localhost", 5001);
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        ) {
            writer.println(this.adminPassword); // Send password first for authentication
            writer.println(command);

            String response = reader.readLine();
            if ("ERROR:AUTH_FAILED".equals(response)) {
                throw new IOException("Authentication failed. Check your ADMIN_PASSWORD and restart the server.");
            }
            return response;
        }
    }

    /**
     * Parses the main JSON data blob from the server and updates all relevant UI components.
     * @param json The JSON string received from the server.
     */
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
            Pattern clientPattern = Pattern.compile("\\{\"clientId\":\"(.*?)\",\"type\":\"(.*?)\",\"lastSeen\":(\\d+)\\}");
            Matcher clientMatcher = clientPattern.matcher(json);
            while (clientMatcher.find()) {
                clients.add(new ClientInfo(clientMatcher.group(1), clientMatcher.group(2), Long.parseLong(clientMatcher.group(3)), formatter));
            }
            clientTable.setItems(FXCollections.observableArrayList(clients));

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
            e.printStackTrace();
        }
    }

    /**
     * A utility method for displaying a modal error dialog to the user.
     * @param title The title of the dialog window.
     * @param message The main content message.
     */
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Overridden method to ensure the background refresh thread is cleanly shut down when the application closes.
     */
    @Override
    public void stop() {
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
    }
}