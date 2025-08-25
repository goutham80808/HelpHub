package com.helphub.client;

import com.helphub.common.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The main entry point for the HelpHub command-line client.
 * <p>
 * This class is responsible for parsing command-line arguments, managing the
 * auto-reconnect loop, and dedicating a separate thread for handling user input from the console.
 * The main thread's primary responsibility is to maintain a connection to the server.
 */
public class Client {

    /** The unique identifier for this client instance. */
    static String clientId;

    /**
     * A volatile reference to the current connection manager.
     * 'volatile' ensures that changes made by the main thread are visible to the UserInputHandler thread.
     */
    static volatile ConnectionManager connectionManager;

    /**
     * The main method executed when the client is run. It parses arguments and enters the persistent reconnect loop.
     * @param args Command-line arguments, e.g., --id alpha --server 192.168.1.10
     */
    public static void main(String[] args) {
        // Default server address if not provided via arguments
        String serverAddress = "localhost";

        // Parse command-line arguments for client ID and server address
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--id":
                    if (i + 1 < args.length) clientId = args[++i];
                    break;
                case "--server":
                    if (i + 1 < args.length) serverAddress = args[++i];
                    break;
            }
        }

        // The --id argument is mandatory for the CLI client
        if (clientId == null) {
            System.err.println("Error: --id <your-id> is required to run from the command line.");
            return;
        }

        System.out.println("HelpHub Client '" + clientId + "' starting...");
        System.out.println("Attempting to connect to server at: " + serverAddress);

        // Start a dedicated thread to handle blocking console input.
        // This prevents the main connection management loop from being blocked by user input.
        Thread userInputThread = new Thread(new UserInputHandler());
        userInputThread.setDaemon(true); // Allows the main application to exit even if this thread is waiting
        userInputThread.start();

        // --- Main Auto-Reconnect Loop ---
        long currentBackoff = Config.getInt("reconnect.backoff.initial", 1000);
        final String finalServerAddress = serverAddress; // Effectively final for use in the loop
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Attempt to establish and maintain a connection
                connectionManager = new ConnectionManager(clientId, finalServerAddress);
                connectionManager.connectAndListen();
            } catch (Exception e) {
                // This block is entered when the connection is lost for any reason
                System.err.println("Connection cycle ended: " + e.getMessage());
            }

            // Wait before attempting to reconnect, using an exponential backoff strategy
            System.err.println("Disconnected. Retrying in " + (currentBackoff / 1000) + "s...");
            try {
                TimeUnit.MILLISECONDS.sleep(getJitteryBackoff(currentBackoff));
            } catch (InterruptedException interruptedException) {
                // If interrupted while sleeping, exit the loop gracefully
                Thread.currentThread().interrupt();
                break;
            }
            // Increase the backoff delay for the next attempt, up to a configured maximum
            currentBackoff = Math.min(currentBackoff * 2, Config.getInt("reconnect.backoff.max", 30000));
        }
    }

    /**
     * Calculates an exponential backoff delay with added jitter.
     * Jitter prevents multiple clients from trying to reconnect at the exact same time.
     * @param baseDelay The base delay in milliseconds.
     * @return The delay plus or minus a random percentage (jitter).
     */
    private static long getJitteryBackoff(long baseDelay) {
        double jitter = Config.getDouble("reconnect.backoff.jitter", 0.2);
        double randomJitter = (ThreadLocalRandom.current().nextDouble(2.0) - 1.0) * jitter;
        return baseDelay + (long) (baseDelay * randomJitter);
    }

    /**
     * A simple Runnable that runs in its own thread to handle blocking I/O from the system console.
     */
    private static class UserInputHandler implements Runnable {
        @Override
        public void run() {
            System.out.println("Commands: /to <id> <message>, /all <message>, /sos <message>. Press Ctrl+C to exit.");
            try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
                String userInput;
                // Block here, waiting for the user to type a line
                while ((userInput = consoleReader.readLine()) != null) {
                    if (connectionManager != null) {
                        connectionManager.handleUserInput(userInput);
                    } else {
                        System.out.println("Not connected. Message not sent.");
                    }
                }
            } catch (IOException e) {
                // This can happen if the console stream is closed. The thread will simply exit.
            }
        }
    }
}