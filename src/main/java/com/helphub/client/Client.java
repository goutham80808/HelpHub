// src/main/java/com/helphub/client/Client.java
package com.helphub.client;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;

public class Client {
    private static final String SERVER_ADDRESS = "localhost"; // Change to server's LAN IP for hotspot test
    private static final int SERVER_PORT = 5000;

    public static void main(String[] args) {
        System.out.println("HelpHub Client starting...");

        try (
                Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)
        ) {
            System.out.println("Connected to the HelpHub server. Start typing messages.");
            System.out.println("Press Ctrl+C to exit.");

            // Thread to listen for messages from the server
            Thread listenerThread = new Thread(new ServerListener(socket));
            listenerThread.start();

            // Add a shutdown hook to cleanly close the socket on Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down client...");
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore, as we are shutting down
                }
            }));

            // Main thread loop to read console input and send to server
            String userInput;
            while ((userInput = consoleReader.readLine()) != null) {
                writer.println(userInput);
            }

        } catch (UnknownHostException ex) {
            System.err.println("Server not found: " + ex.getMessage());
        } catch (IOException ex) {
            System.err.println("I/O error: " + ex.getMessage());
        } finally {
            System.out.println("Client disconnected.");
        }
    }

    /**
     * A runnable task that continuously listens for messages from the server
     * and prints them to the console.
     */
    private static class ServerListener implements Runnable {
        private final Socket socket;

        public ServerListener(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                String serverMessage;
                while ((serverMessage = reader.readLine()) != null) {
                    System.out.println(serverMessage);
                }
            } catch (IOException e) {
                // This exception occurs when the client socket is closed,
                // which is expected on shutdown or server disconnect.
                if (!socket.isClosed()) {
                    System.err.println("Connection to server lost.");
                }
            }
        }
    }
}