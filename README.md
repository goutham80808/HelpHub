# HelpHub

HelpHub is a disaster-resilient, offline-first communication system built in Java. It operates entirely on a local Wi-Fi hotspot or LAN without any dependency on the Internet. This enables survivors and rescue workers to send and receive messages during natural disasters or other emergency scenarios when conventional networks are down.

## Core Features

*   **Offline-First Communication**: Operates on any local network (LAN/Hotspot) with no internet required.
*   **Encrypted Transport**: All communication is secured with TLS, protecting messages from eavesdropping on the network.
*   **Guaranteed Messaging**: Uses a store-and-forward SQLite database to queue messages for offline users, ensuring delivery upon their return.
*   **Reliable Connections**: Features automatic client reconnect with exponential backoff and server-side cleanup of dead connections.
*   **Priority System**: Supports `HIGH` priority messages (e.g., `/sos`) that are delivered before normal messages.
*   **Admin Monitoring**: A built-in command-line dashboard for server administrators to monitor system status, online clients, and pending messages in real-time.

## How to Build

### Prerequisites
*   Java 17 (or newer)
*   Apache Maven

### Build Command
Navigate to the project's root directory (where `pom.xml` is located) and run the following command:
```bash
mvn clean package
```
This will compile the code and create a single, executable "fat JAR" in the `target/` directory (e.g., `helphub-0.1.0.jar`) that contains all necessary dependencies.

## One-Time Setup: Generating the Security Key

Before running the system for the first time, you must generate the self-signed certificate that enables TLS encryption.

1.  **Ensure Bouncy Castle is in `pom.xml`**: Your `pom.xml` should include the `bcprov-jdk15on` dependency.
2.  **Run the KeyUtil**: Execute the following Maven command from your project root to generate the key:
    ```bash
    mvn exec:java -Dexec.mainClass="com.helphub.security.KeyUtil"
    ```
3.  This creates a **`helphub.keystore`** file in your project directory. This file is essential for both the server and the clients.
4.  Add `*.keystore` to your `.gitignore` file to avoid committing it to version control.

## How to Run

### 1. Run the Server
The server requires the keystore password to be set as an environment variable to decrypt its private key.

*   **On Linux/macOS:**
    ```bash
    export KEYSTORE_PASSWORD="HelpHubPassword"
    java -cp target/helphub-0.1.0.jar com.helphub.server.RelayServer
    ```
*   **On Windows (Command Prompt):**
    ```bash
    set KEYSTORE_PASSWORD="HelpHubPassword"
    java -cp target/helphub-0.1.0.jar com.helphub.server.RelayServer
    ```
The server will start, load the keystore, and begin listening for secure client connections.

### 2. Run the Client
1.  **Copy the Keystore**: Copy the `helphub.keystore` file from the server's project directory to the directory where you will run the client application. The client needs this file to trust the server's certificate.
2.  **Run the Command**: Open a new terminal and run the following command, providing a unique ID for each client:
    ```bash
    java -cp target/helphub-0.1.0.jar com.helphub.client.Client --id alpha
    ```
    *(Replace `alpha` with a unique ID like `rescue-team-1`, `medic-base`, etc.)*

## How to Use

### Client Chat Commands
-   `/to <recipientId> <message>`: Sends a private, normal-priority message to a specific user.
-   `/all <message>`: Sends a public, normal-priority message to all other users.
-   `/sos <message>`: Sends a public, **HIGH**-priority message to all users. This is for emergencies and will be delivered before any queued normal-priority messages.

### Admin Console Commands (Server-Side)
Type these commands directly into the terminal where the `RelayServer` is running.

-   `stats`: Displays a summary of online clients and pending/total message counts.
-   `clients`: Lists all currently connected clients and their last activity time.
-   `pending <clientId>`: Shows the queue of undelivered messages for a specific client.
-   `tail <n>`: Displays the last `n` lines from the `logs/messages.log` file (defaults to 10).
-   `help`: Shows the list of available admin commands.

## Technical Details

### Configuration
Network timing (heartbeats, timeouts, reconnect delays) can be tuned in the `src/main/resources/config.properties` file.

### Database Schema
The server uses an SQLite database located at `data/emergency.db`.
-   **`clients`**: Stores client IDs and their last-seen timestamp.
-   **`messages`**: Stores all message details, including sender, recipient, content, status (`PENDING`/`DELIVERED`), and `priority`.