# 🚨 HelpHub (Command-Line Edition)

**HelpHub** is a **disaster-resilient, offline-first communication system** built in Java.
It runs entirely on a local Wi-Fi hotspot or LAN — **no Internet required** — enabling survivors and rescue workers to send and receive messages when conventional networks are down.

---

##  Features

1. **Offline-First Communication**
   Works over any local network (LAN/Hotspot) with **zero Internet dependency**.

2. **End-to-End Security**
   Secured with **TLS encryption**, preventing eavesdropping.

3. **Guaranteed Messaging**
   Embedded **SQLite store-and-forward queue** ensures delivery once recipients reconnect.

4. **Reliable Connections**
   Automatic client reconnect with **exponential backoff** and server-side cleanup of dead sessions.

5. **Priority System**
   High-priority alerts (e.g., `/sos`) are always delivered before normal messages.

6. **Admin Console**
   Built-in CLI for real-time monitoring: connected clients, message queues, and system logs.

---

## Build Instructions

### Prerequisites

* Java **17+**
* [Apache Maven](https://maven.apache.org/)

### Build

```bash
mvn clean package
```

This generates a self-contained executable JAR at:

```
target/helphub-0.1.0.jar
```

---

##  TLS Security Setup (One-Time)

Before first run, generate the self-signed certificate:

**Linux/macOS or CMD**

```bash
mvn exec:java -Dexec.mainClass="com.helphub.security.KeyUtil"
```

**PowerShell**

```powershell
mvn exec:java "-Dexec.mainClass=com.helphub.security.KeyUtil"
```

This creates a keystore file:

```
helphub.keystore
```

Both server and clients require this file.

---

##  Running HelpHub

### 1. Start the Server

Find your machine’s local IP (e.g., `192.168.1.10`).
Set the keystore password as an environment variable:

**Linux/macOS**

```bash
export KEYSTORE_PASSWORD="HelpHubPassword"
java -cp target/helphub-0.1.0.jar com.helphub.server.RelayServer
```

**Windows (PowerShell)**

```powershell
$env:KEYSTORE_PASSWORD="HelpHubPassword"
java -cp target/helphub-0.1.0.jar com.helphub.server.RelayServer
```

---

### 2. Run a Client

Copy these files from the server machine to each client to the same directory:

* `target/helphub-0.1.0.jar`
* `helphub.keystore`

Start a client (replace `<server-ip>` with the server address):

```bash
java -cp helphub-0.1.0.jar com.helphub.client.Client --id alpha --server 192.168.1.10
```

---

##  Usage Guide

### Client Commands

* `/to <recipientId> <message>` → Private message
* `/all <message>` → Broadcast to all
* `/sos <message>` → High-priority emergency alert

### Admin Console (Server-Side)

* `stats` → Show summary of online clients and message counts
* `clients` → List connected clients
* `pending <clientId>` → Show queued undelivered messages
* `tail <n>` → Show last *n* log lines from `logs/messages.log`
* `help` → Show all admin commands

---

##  Notes

* Works best on a **local Wi-Fi hotspot** without Internet.
* Designed for **emergency communication** when mobile networks are down.

---
