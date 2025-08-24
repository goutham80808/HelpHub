// FILE: src/main/java/com/helphub/client/Client.java
package com.helphub.client;

import com.helphub.common.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class Client {
    static String clientId;
    static volatile ConnectionManager connectionManager;

    public static void main(String[] args) {
        String serverAddress = "localhost"; // Default value

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

        if (clientId == null) {
            clientId = "node-" + String.format("%06x", ThreadLocalRandom.current().nextInt(0, 0xFFFFFF + 1));
            System.out.println("No --id specified. Using generated ID: " + clientId);
        }
        System.out.println("HelpHub Client '" + clientId + "' starting...");
        System.out.println("Attempting to connect to server at: " + serverAddress);

        Thread userInputThread = new Thread(new UserInputHandler());
        userInputThread.setDaemon(true);
        userInputThread.start();

        long currentBackoff = Config.getInt("reconnect.backoff.initial", 1000);
        final String finalServerAddress = serverAddress;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                connectionManager = new ConnectionManager(clientId, finalServerAddress);
                connectionManager.connectAndListen();
            } catch (Exception e) {
                System.err.println("Connection cycle ended: " + e.getMessage());
            }

            System.err.println("Disconnected. Retrying in " + (currentBackoff / 1000) + "s...");
            try {
                TimeUnit.MILLISECONDS.sleep(getJitteryBackoff(currentBackoff));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            currentBackoff = Math.min(currentBackoff * 2, Config.getInt("reconnect.backoff.max", 30000));
        }
    }

    private static long getJitteryBackoff(long baseDelay) {
        double jitter = Config.getDouble("reconnect.backoff.jitter", 0.2);
        double randomJitter = (ThreadLocalRandom.current().nextDouble(2.0) - 1.0) * jitter;
        return baseDelay + (long) (baseDelay * randomJitter);
    }

    private static class UserInputHandler implements Runnable {
        @Override
        public void run() {
            System.out.println("Commands: /to <id> <msg>, /all <msg>, /sos <msg>. Press Ctrl+C to exit.");
            try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
                String userInput;
                while ((userInput = consoleReader.readLine()) != null) {
                    if (connectionManager != null) {
                        connectionManager.handleUserInput(userInput);
                    } else {
                        System.out.println("Not connected. Message not sent.");
                    }
                }
            } catch (IOException e) { /* Console read failed */ }
        }
    }
}