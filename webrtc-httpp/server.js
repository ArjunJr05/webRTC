// WebRTC Signaling Server (Node.js equivalent of DemoApplication.java)
// Mirrors the Spring Boot SignalingHandler exactly:
//   - Max 2 clients at a time
//   - First client  → {"type":"caller"}
//   - Second client → {"type":"receiver"}
//   - All messages relayed to the OTHER peer

import { WebSocketServer } from "ws";
import http from "http";

const PORT = 8081;

const server = http.createServer((req, res) => {
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end("WebRTC Signaling Server running\n");
});

const wss = new WebSocketServer({ server, path: "/ws" });

const sessions = new Set();
let callerAssigned = false;

wss.on("connection", (socket, req) => {
    if (sessions.size >= 2) {
        console.log("Already 2 clients — rejecting");
        socket.close();
        return;
    }

    sessions.add(socket);
    socket.isAlive = true;

    const isCaller = !callerAssigned;
    callerAssigned = isCaller ? true : callerAssigned;

    const role = isCaller ? "caller" : "receiver";
    socket.send(JSON.stringify({ type: role }));
    console.log(`Client connected as ${role}. Total: ${sessions.size}`);

    socket.on("message", (data) => {
        const text = data.toString();
        // Relay to the other peer
        for (const s of sessions) {
            if (s !== socket && s.readyState === s.OPEN) {
                s.send(text);
            }
        }
    });

    socket.on("close", () => {
        sessions.delete(socket);
        if (sessions.size === 0) {
            callerAssigned = false; // reset so next pair can start fresh
        }
        console.log(`Client disconnected. Total: ${sessions.size}`);
    });

    socket.on("error", (err) => {
        console.error("Socket error:", err.message);
        sessions.delete(socket);
        if (sessions.size === 0) callerAssigned = false;
    });
});

server.listen(PORT, "0.0.0.0", () => {
    console.log(`Signaling server listening on port ${PORT}`);
    console.log(`WebSocket endpoint: ws://<your-pc-ip>:${PORT}/ws`);
    console.log(`Local test URL:     ws://127.0.0.1:${PORT}/ws`);
});
