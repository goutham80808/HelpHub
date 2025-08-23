# HelpHub

HelpHub is a disaster-resilient, offline-first communication system built in Java. It operates entirely on a local Wi-Fi hotspot or LAN without any dependency on the Internet. This enables survivors and rescue workers to send and receive messages during natural disasters or other emergency scenarios when conventional networks are down.

## Phase 2: Directed Messages & Protocol

This phase introduces client identities and a structured JSON protocol, enabling both directed (one-to-one) and broadcast (one-to-all) messaging.

## Phase 3: Offline Store-and-Forward

The system is now resilient to client disconnects. The server uses an SQLite database (`data/emergency.db`) to store all messages. If a message is sent to a client that is offline, the server holds it. When the client reconnects, the server immediately delivers all pending messages.

### How It Works
1.  All messages sent are first saved to the server's database with a `PENDING` status.
2.  The server attempts to deliver the message to online recipients.
3.  When a client receives a message, it automatically sends an `ACK` (acknowledgment) back to the server.
4.  Upon receiving the `ACK`, the server updates the message's status to `DELIVERED` in the database.
5.  When a client connects, the server queries the database for any `PENDING` messages for that client's ID and sends them.

### Demo the Offline Feature
1.  Start the server.
2.  Start two clients, `alpha` and `bravo`.
3.  From `alpha`, send a broadcast: `/all Testing 123`. Verify `bravo` receives it.
4.  **Close the `bravo` client** (Ctrl+C).
5.  From `alpha`, send a direct message to the offline client: `/to bravo This message is for you while you are offline.`
6.  From `alpha`, send another broadcast: `/all This is a broadcast while bravo is away.`
7.  **Restart the `bravo` client**.
8.  **Observe**: As soon as `bravo` connects, it will immediately receive both the direct message and the broadcast message it missed, tagged as `(delayed)`.

### Database Schema
-   **`clients`**: Stores client IDs and their last seen timestamp.
-   **`messages`**: Stores message details, including `from_client`, `to_client`, `status` (`PENDING`/`DELIVERED`), and content.

## How to Build

### Prerequisites
- Java 17 (or newer)
- Apache Maven

### Build Command
Navigate to the project's root directory (where `pom.xml` is located) and run the following command:
```bash
mvn clean package
```

### 1. Run the Server
```bash
java -cp target/helphub-0.1.0.jar com.helphub.server.RelayServer
```
### 2. Run Clients with IDs
```bash
java -cp target/helphub-0.1.0.jar com.helphub.client.Client --id args
```
