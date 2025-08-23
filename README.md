# HelpHub

HelpHub is a disaster-resilient, offline-first communication system built in Java. It operates entirely on a local Wi-Fi hotspot or LAN without any dependency on the Internet. This enables survivors and rescue workers to send and receive messages during natural disasters or other emergency scenarios when conventional networks are down.

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
