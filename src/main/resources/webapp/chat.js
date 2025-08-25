// FILE: src/main/resources/webapp/chat.js

/**
 * This script is the core of the HelpHub web-based chat client.
 * It runs entirely in the user's browser and handles:
 * - Generating a memorable client ID.
 * - Establishing and maintaining a WebSocket connection to the RelayServer.
 * - Handling user input for sending messages.
 * - Parsing different message commands (`/to`, `/sos`).
 * - Displaying incoming and outgoing messages in the UI.
 * - Automatically attempting to reconnect if the connection is lost.
 */
document.addEventListener('DOMContentLoaded', () => {
    // --- UI Element References ---
    const statusDiv = document.getElementById('status');
    const messagesDiv = document.getElementById('messages');
    const messageInput = document.getElementById('message-input');
    const sendButton = document.getElementById('send-button');

    let websocket;
    let clientId;

    /**
     * Asynchronously generates a unique, memorable client ID.
     * It fetches two wordlists, picks one word from each, and combines them.
     * This is much more user-friendly than a random string or number.
     * @returns {Promise<string>} A promise that resolves to the generated client ID.
     */
    async function generateClientId() {
        try {
            const adjResponse = await fetch('adjectives.txt');
            const nounResponse = await fetch('nouns.txt');
            if (!adjResponse.ok || !nounResponse.ok) {
                throw new Error('Failed to fetch wordlists from server.');
            }
            const adjText = await adjResponse.text();
            const nounText = await nounResponse.text();

            // Trim each line to remove whitespace and Windows-style carriage returns (\r).
            // Filter out any empty lines that might result.
            const adjectives = adjText.split('\n').map(line => line.trim()).filter(Boolean);
            const nouns = nounText.split('\n').map(line => line.trim()).filter(Boolean);

            const randomAdj = adjectives[Math.floor(Math.random() * adjectives.length)];
            const randomNoun = nouns[Math.floor(Math.random() * nouns.length)];

            return `${randomAdj}-${randomNoun}`;
        } catch (error) {
            console.error("Could not load wordlists, falling back to a random numeric ID.", error);
            return `web-${Math.floor(1000 + Math.random() * 9000)}`;
        }
    }

    /**
     * Initializes the client. It retrieves the client ID from session storage
     * or generates a new one, then initiates the WebSocket connection.
     */
    async function initialize() {
        clientId = sessionStorage.getItem('helphubClientId');
        if (!clientId) {
            clientId = await generateClientId();
            sessionStorage.setItem('helphubClientId', clientId);
        }
        connect();
    }

    /**
     * Establishes a WebSocket connection to the server and defines all event handlers.
     */
    function connect() {
        // Construct the WebSocket URL based on the current page's host and protocol.
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/chat`;
        websocket = new WebSocket(wsUrl);

        // --- WebSocket Event Handlers ---

        websocket.onopen = () => {
            statusDiv.textContent = `Connected as: ${clientId}`;
            statusDiv.style.color = 'green';
            messageInput.disabled = false;
            sendButton.disabled = false;
            addSystemMessage('Connected. Use /to <id> <msg> or /sos <msg>.');

            // The first message sent must be a registration message.
            const registrationMessage = { from: clientId, type: 'STATUS', body: 'register', priority: 1 };
            websocket.send(JSON.stringify(registrationMessage));
        };

        websocket.onmessage = (event) => {
            const messageData = JSON.parse(event.data);
            addMessage(messageData, false);
        };

        websocket.onclose = () => {
            statusDiv.textContent = 'Disconnected. Retrying...';
            statusDiv.style.color = 'red';
            messageInput.disabled = true;
            sendButton.disabled = true;
            addSystemMessage('Connection lost. Reconnecting in 3 seconds...');
            // Simple, robust auto-reconnect logic.
            setTimeout(connect, 3000);
        };

        websocket.onerror = (error) => {
            console.error('WebSocket Error:', error);
            statusDiv.textContent = 'Connection Error';
        };
    }

    /**
     * Renders a message object in the chat UI.
     * @param {object} messageData - The message object, containing from, to, body, type, etc.
     * @param {boolean} isLocal - True if the message was sent by this client, false otherwise.
     */
    function addMessage(messageData, isLocal) {
        const messageDiv = document.createElement('div');
        messageDiv.classList.add('message', isLocal ? 'local' : 'remote');

        // Apply special styling based on message priority or type.
        if (messageData.priority === 2) { // HIGH
            messageDiv.classList.add('sos');
        } else if (messageData.type === 'DIRECT') {
            messageDiv.classList.add('direct');
        }

        const senderDiv = document.createElement('div');
        senderDiv.classList.add('sender');

        // Add a (delayed) tag for messages that were queued on the server.
        const isDelayed = !isLocal && (Date.now() - messageData.timestamp) > 30000; // 30 seconds
        let senderText = isDelayed ? "(delayed) " + messageData.from : messageData.from;
        if (messageData.type === 'DIRECT') senderText += ` (Direct to ${messageData.to})`;
        if (messageData.priority === 2) senderText += ' [SOS]';
        senderDiv.textContent = senderText;

        messageDiv.appendChild(senderDiv);
        messageDiv.appendChild(document.createTextNode(messageData.body));

        // Insert new messages at the top, which appears as the bottom due to CSS flex-direction.
        messagesDiv.insertBefore(messageDiv, messagesDiv.firstChild);
    }

    /**
     * Renders a system message (e.g., "Connected") in the chat UI.
     * @param {string} text - The content of the system message.
     */
    function addSystemMessage(text) {
        const systemMessageDiv = document.createElement('div');
        systemMessageDiv.textContent = `--- ${text} ---`;
        systemMessageDiv.style.textAlign = 'center';
        systemMessageDiv.style.color = '#888';
        systemMessageDiv.style.fontSize = '0.9em';
        messagesDiv.insertBefore(systemMessageDiv, messagesDiv.firstChild);
    }

    /**
     * Parses the user's input, constructs a message object, sends it, and updates the UI.
     */
    function sendMessage() {
        const messageText = messageInput.value;
        if (messageText.trim() === '' || !websocket || websocket.readyState !== WebSocket.OPEN) return;

        let message;

        if (messageText.startsWith('/to ')) {
            const parts = messageText.split(' ');
            if (parts.length >= 3) {
                const recipientId = parts[1];
                const body = parts.slice(2).join(' ');
                message = { from: clientId, to: recipientId, type: 'DIRECT', body: body, priority: 1 };
            } else {
                addSystemMessage("Invalid format: /to <recipientId> <message>");
                return;
            }
        } else if (messageText.startsWith('/sos ')) {
            const body = messageText.substring(5);
            message = { from: clientId, to: null, type: 'BROADCAST', body: body, priority: 2 }; // HIGH priority
        } else {
            message = { from: clientId, to: null, type: 'BROADCAST', body: messageText, priority: 1 }; // NORMAL priority
        }

        websocket.send(JSON.stringify(message));
        addMessage(message, true); // Optimistically display our own message
        messageInput.value = '';
    }

    // --- Event Listener Bindings ---
    sendButton.addEventListener('click', sendMessage);
    messageInput.addEventListener('keypress', (event) => {
        if (event.key === 'Enter') sendMessage();
    });

    // --- Initial Entry Point ---
    initialize();
});