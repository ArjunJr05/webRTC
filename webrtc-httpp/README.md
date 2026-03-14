**WebRTCHttp**

JavaScript Library Documentation

v1.0.0 • ESM Module • MIT License

# Overview

WebRTCHttp is a lightweight JavaScript library that simplifies WebRTC peer-to-peer connections using HTTP-based signaling. It uses standard HTTP polling to exchange WebRTC handshake data — offers, answers, and ICE candidates — through a REST backend.

**Key features:** Simple callback-based API, automatic ICE candidate management, built-in pending candidate queue, and full ESM module support.

# Installation

## Import

**import { WebRTCHttp } from "webrtc-http";**

## package.json (library)

**{**

**"name": "webrtc-http",**

**"version": "1.0.0",**

**"type": "module",**

**"main": "src/index.js",**

**"exports": { ".": "./src/index.js" }**

**}**

# Quick Start

**import { WebRTCHttp } from "webrtc-http";**

**const rtc = new WebRTCHttp({**

**baseURL: "https://your-server.com",**

**rtcConfig: {**

**iceServers: \[{ urls: "stun:stun.l.google.com:19302" }\]**

**}**

**});**

**rtc.onopen = () => console.log("Connected!");**

**rtc.onmessage = (data) => console.log("Received:", data);**

**rtc.onclose = () => console.log("Disconnected");**

**rtc.onerror = (err) => console.error("Error:", err);**

**await rtc.connect();**

**rtc.send("Hello, peer!");**

# Constructor

**new WebRTCHttp({ baseURL, rtcConfig, pollInterval })**

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| baseURL | string | required | Base URL of your signaling server |
| rtcConfig | object | {} | RTCPeerConnection config (STUN/TURN servers) |
| pollInterval | number | 1000 | Polling interval in milliseconds |

## rtcConfig Example

**rtcConfig: {**

**iceServers: \[**

**{ urls: "stun:stun.l.google.com:19302" },**

**{**

**urls: "turn:your-turn-server.com:3478",**

**username: "user",**

**credential: "password"**

**}**

**\]**

**}**

# Properties

## Public Properties

| Property | Type | Description |
| --- | --- | --- |
| rtc.peer | RTCPeerConnection | The underlying RTCPeerConnection instance |
| rtc.dataChannel | RTCDataChannel | The active data channel (null before connect) |
| rtc.isCaller | boolean | True if this peer created the offer |

## Event Callbacks

Assign these before calling connect(). All are null by default.

| Callback | Signature | Fired when |
| --- | --- | --- |
| rtc.onopen | () => void | DataChannel opens and is ready to send |
| rtc.onmessage | (data) => void | A message or binary chunk is received from the peer |
| rtc.onclose | () => void | DataChannel closes |
| rtc.onerror | (err) => void | Any connection or channel error occurs |

**Important:** Set rtc.onopen before calling await rtc.connect() — the DataChannel may open during connect() and you would miss the event if set after.

# Methods

## connect()

**await rtc.connect()**

Initiates the WebRTC connection. Checks the signaling server for an existing offer:

*   No offer exists → **Caller**: creates DataChannel, generates offer, posts it, polls for answer
*   Offer exists → **Receiver**: reads offer, creates answer, posts it back, DataChannel opens automatically

**Returns:** Promise<void>. Errors are caught and forwarded to rtc.onerror.

## send(message)

**rtc.send(message)**

| Parameter | Type | Description |
| --- | --- | --- |
| message | string | ArrayBuffer | Uint8Array | Data to send to the peer |

Logs a warning if the DataChannel is not open. Check rtc.dataChannel.readyState === 'open' beforehand if needed.

## close()

**rtc.close()**

Cleanly shuts down the connection:

*   Clears all polling timers (answer and candidate)
*   Clears the pending candidate queue
*   Closes the DataChannel
*   Closes the RTCPeerConnection

# How It Works

## Connection Flow

**Peer A (Caller) Server Peer B (Receiver)**

**──────────────────────────────────────────────────────────────────────────────**

**connect()**

**GET /signal/offer ───► (empty)**

**◄── null**

**POST /signal/offer ───► stores offer**

**poll /signal/answer**

**connect()**

**GET /signal/offer**

**returns offer ───►**

**POST /signal/answer**

**◄─── stores answer**

**◄── receives answer**

**setRemoteDescription()**

**\[ICE polling begins\]**

**DataChannel opens ◄──────────────────────────────► DataChannel opens**

## ICE Candidate Exchange

*   Caller posts candidates to /signal/candidates/true
*   Receiver posts candidates to /signal/candidates/false
*   Each peer polls /signal/candidates/{isCaller} — the server returns the opposite side's candidates
*   Candidates arriving before remoteDescription is set are queued and flushed once ready
*   Polling stops automatically when connection state reaches "connected"

## Pending Candidate Queue

If ICE candidates arrive before setRemoteDescription() has been called, they are stored in \_pendingCandidates and flushed via \_flushPendingCandidates() once the remote description is ready.

# Required Backend API

The lib expects these 6 REST endpoints. All request and response bodies are JSON.

## POST /signal/offer

Store the caller's SDP offer.

**Request Body**

**{ "type": "offer", "sdp": "v=0\\r\\n..." }**

## GET /signal/offer

Retrieve and consume the offer. Return empty object {} if none exists.

**Response Body**

**{ "type": "offer", "sdp": "v=0\\r\\n..." }**

**// or {} if no offer exists**

## POST /signal/answer

Store the receiver's SDP answer.

**Request Body**

**{ "type": "answer", "sdp": "v=0\\r\\n..." }**

## GET /signal/answer

Retrieve and consume the answer. Return empty object {} if none exists.

## POST /signal/candidates/{isCaller}

Store an ICE candidate. isCaller is true or false.

**Request Body**

**{ "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 }**

## GET /signal/candidates/{isCaller}

Retrieve and consume the **opposite** side's candidates. If isCaller=true, return receiver's candidates and vice versa.

**Response Body**

**\[**

**{ "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 },**

**{ "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 1 }**

**\]**

# Important Notes

## SDP Must Be Stored as Object, Not String

Store SDP as a plain object in your backend. Double-serializing (storing as a JSON string) causes TypeError: Failed to execute 'setRemoteDescription'.

**Java — correct**

**private final Map<String, Object> offer = new ConcurrentHashMap<>();**

**offer.put(id, body); // ✓ store as Object**

**// NOT:**

**// offer.put(id, mapper.writeValueAsString(body.get("sdp"))); ✗**

## onopen Must Be Set Before connect()

The DataChannel may open during connect() execution. Always set rtc.onopen before await rtc.connect().

**// ✓ Correct**

**rtc.onopen = () => { ... };**

**await rtc.connect();**

**// ✗ Wrong — may miss the event**

**await rtc.connect();**

**rtc.onopen = () => { ... };**

## Browser-Only

Uses RTCPeerConnection and fetch — browser APIs only. Not compatible with Node.js without polyfills.

## One Room, Two Peers

Designed for exactly two peers per connection. The first peer becomes the Caller, the second the Receiver.

# Error Handling

*   connect() errors are caught and forwarded to rtc.onerror
*   Polling errors (answer, candidates) are console.error'd — the timer continues on the next interval
*   ICE candidate send failures are forwarded to rtc.onerror
*   All fetch calls return safe fallbacks (null or \[\]) on failure — they never throw unexpectedly

# Full Usage Example

**import { WebRTCHttp } from "webrtc-http";**

**const rtc = new WebRTCHttp({**

**baseURL: "https://your-server.com",**

**rtcConfig: {**

**iceServers: \[**

**{ urls: "stun:stun.l.google.com:19302" },**

**{**

**urls: "turn:your-turn.com:3478",**

**username: "user",**

**credential: "pass"**

**}**

**\]**

**},**

**pollInterval: 1000**

**});**

**// Set ALL callbacks before connect()**

**rtc.onopen = () => console.log("DataChannel open");**

**rtc.onmessage = (data) => {**

**if (typeof data === "string") {**

**console.log("Text:", data);**

**} else {**

**console.log("Binary:", data.byteLength, "bytes");**

**}**

**};**

**rtc.onclose = () => console.log("Peer disconnected");**

**rtc.onerror = (err) => console.error("RTC Error:", err);**

**await rtc.connect();**

**console.log("isCaller:", rtc.isCaller);**

**// Cleanup signaling after connection**

**rtc.peer.onconnectionstatechange = () => {**

**if (rtc.peer.connectionState === "connected") {**

**fetch("/signal/cleanup", { method: "POST" });**

**}**

**};**

**// Send a text message**

**rtc.send("Hello!");**

**// Send a binary chunk**

**const buffer = await file.arrayBuffer();**

**rtc.dataChannel.send(new Uint8Array(buffer));**

**// Close when done**

**rtc.close();**
