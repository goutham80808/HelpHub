# HelpHub v2.0

HelpHub is a **disaster-resilient, offline-first communication system** built in Java. It operates entirely on a **local Wi-Fi hotspot or LAN**, without Internet dependency, enabling **survivors and rescue workers** to send and receive messages when conventional networks are down.

---

## Table of Contents

1. [Core Principles](#1-core-principles)
2. [System Architecture](#2-system-architecture)
3. [Communication Flow & Protocols](#3-communication-flow--protocols)
4. [Persistence & Security Model](#4-persistence--security-model)
5. [Project Structure](#5-project-structure)
6. [Build & Setup (Developers)](#6-build--setup-developers)
7. [Running the System (Operators & End Users)](#7-running-the-system-operators--end-users)
8. [Key Components (Code Tour)](#8-key-components-code-tour)
9. [Credits](#9-created-by)

---

## 1. Core Principles

* **Offline-First:** Works fully without Internet; all communication is LAN-based.
* **Lightweight & Dependency-Free:** Built on standard Java (Sockets, Threads, JDBC) with minimal libs (Jetty, SLF4J). No heavy frameworks like Spring.
* **Resilience > Consistency:** At-least-once delivery with guaranteed persistence, even if the server crashes.
* **Multi-Platform:** Two client types:

    * Secure CLI (Java, TLS) for responders
    * Web client (browser) for survivors
* **Security-Focused:** TLS-encrypted transport for CLI clients; threat model assumes passive eavesdropping protection.

---

## 2. System Architecture

```
                               +--------------------------------------------+
                               |         HelpHub Relay Server (Java)        |
                               |                                            |
                               |  +--------------------------------------+  |
                               |  |      Core Logic (Message Router)     |  |
                               |  +--------------------------------------+  |
                               |       |                |                 |
+----------------------------> | Port 5000          | Port 8080         | Port 5001
| (TCP/TLS)                    | (Secure TCP)       | (HTTP & WS)       | (Plain TCP)
|                              |                    |                   |
|                              | ClientHandler      | WebSocketHandler  | AdminConnectionHandler
|                              +--------------------+-------------------+--------------------+
|                                      |                    |                   |            |
|                                      +--------------------+-------------------+            |
|                                                           |                                |
|                                             +-------------v-------------+                  |
|                                             |   Persistence (SQLite DB)   |                  |
|                                             +-----------------------------+                  |
|                                                                                            |
+-----------------+            +-----------------+             +-----------------+           |
|   CLI Client    |            |   CLI Client    |             |   Web Client    | <---------+
| (Laptop, Java)  |            | (Laptop, Java)  |             | (Phone Browser) |           
+-----------------+            +-----------------+             +-----------------+           
                                                                                            
                                                     +---------------------------------------+
                                                     |        JavaFX Dashboard               |
                                                     |        (Coordinator)                  |
                                                     +---------------------------------------+
```

---

## 3. Communication Flow & Protocols

### 3.1 Client Registration

* **CLI (TCP/TLS):** First message = plain-text client ID. If taken, server rejects.
* **Web Client (WebSocket):** First JSON message = `STATUS` with ID. If taken, server rejects.

### 3.2 Message Routing

All messages pass through `RelayServer.routeMessageToAll()`:

1. Persist to SQLite (`PENDING`)
2. Route depending on type:

    * **DIRECT:** Send to recipient if connected (TCP/WebSocket). Otherwise kept `PENDING`.
    * **BROADCAST / SOS:** Sent to all clients except sender.

### 3.3 Offline Handling

* **Store-and-Forward:** All messages stored before routing.
* **ACK:** Clients confirm receipt → DB updated to `DELIVERED`.
* **Replay on Connect:** Pending messages replayed in priority order.

### 3.4 Admin Protocol (Port 5001)

1. Dashboard sends password
2. Dashboard sends command (e.g., `GET_DATA`, `ADMIN_KICK alpha`)
3. Server replies with confirmation / JSON blob

---

## 4. Persistence & Security Model

### Persistence

* **`messages`**: Stores full message objects with status + priority
* **`clients`**: Tracks known clients + last seen
* **`schema_version`**: Schema migrations

### Security

* **Threat Model:** Protects against passive Wi-Fi sniffing
* **CLI Clients:** TLS v1.3 over `SSLSocket`
* **Keystore:** Self-signed cert (`helphub.keystore`) used by both server + CLI clients
* **Admin:** Password-authenticated, plain TCP
* **Limitations:** Web clients use `ws://` (unencrypted); malicious authenticated clients not prevented

---

## 5. Project Structure

```
src/main/java/com/helphub/
├── common/       # Shared models & utilities
├── client/       # Command-line client
├── server/       # Relay server + WebSocket support
├── dashboard/    # JavaFX monitoring dashboard
├── security/     # TLS keystore generator
```

* **resources/webapp/** → Web client (HTML/JS)
* **test/** → JUnit tests

---

## 6. Build & Setup (Developers)

### Prerequisites

* Java 17+
* Apache Maven

### Build

```bash
mvn clean package
```

Produces executable JAR → `target/helphub-0.1.0.jar`

### One-Time TLS Keystore

```bash
# PowerShell
mvn exec:java "-Dexec.mainClass=com.helphub.security.KeyUtil"

# Linux/macOS
mvn exec:java -Dexec.mainClass="com.helphub.security.KeyUtil"
```

Generates `helphub.keystore` for server + CLI clients.

---

## 7. Running the System (Operators & End Users)

### 7.1 Start the Server

```bash
# Linux/macOS
export KEYSTORE_PASSWORD="HelpHubPassword"
java -cp target/helphub-0.1.0.jar com.helphub.server.RelayServer

# PowerShell
$env:KEYSTORE_PASSWORD="HelpHubPassword"
java -cp target/helphub-0.1.0.jar com.helphub.server.RelayServer
```

Server prints banner with **`helphub.local`** and **fallback IP**.

### 7.2 Connect as Web Client

* Connect phone to same Wi-Fi
* Open browser → `http://helphub.local:8080` (or fallback IP)
* If the easy address doesn't work, use the fallback IP address that the coordinator announced from the server's screen. 
* The chat interface will load, assign a memorable name (e.g., Brave-Lion), and be ready to use.


### 7.3 Connect as CLI Client

Copy these files to client machine:

* `target/helphub-0.1.0.jar`
* `helphub.keystore`

Run:

```bash
java -cp helphub-0.1.0.jar com.helphub.client.Client --id alpha --server 192.168.43.101
```

### 7.4 Run the Dashboard

```bash
# Set admin password(On linux/macOS)
export ADMIN_PASSWORD="YourSecretAdminPassword"

# PowerShell(On Windows)
$env:ADMIN_PASSWORD="YourSecretAdminPassword"

# Start dashboard
mvn javafx:run "-Dadmin.password=YourSecretAdminPassword"
```

---

## 8. Key Components (Code Tour)

* `RelayServer.java` → main server & routing logic
* `Db.java` → SQLite persistence + migrations
* `WebSocketHandler.java` → web client management
* `Client.java` → CLI client entrypoint
* `ConnectionManager.java` → CLI connection lifecycle
* `KeyUtil.java` → TLS keystore generator

---

## 9. Created by
- [Goutham Krishna Mandati](https://x.com/goutham_808)