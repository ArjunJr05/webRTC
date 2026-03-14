export class WebRTCWS {

    constructor({ signalingUrl, rtcConfig = {}, optional = null }) {

        this.signalingUrl = signalingUrl;
        this.peer = new RTCPeerConnection(rtcConfig);
        this.socket = null;
        this.dataChannel = null;
        this._pendingCandidates = [];
        this.isCaller = false;
        this.optional = optional;

        this.onmessage = null;
        this.onopen = null;
        this.onclose = null;
        this.onerror = null;

        this._setupPeerEvents();
    }

    connect() {
        this.socket = new WebSocket(this.signalingUrl);

        this.socket.onopen = () => {
            console.log("[WS] WebSocket connected");
            this._send({ type: "join" });
        };

        this.socket.onmessage = async (event) => {
            const message = JSON.parse(event.data);

            switch (message.type) {

                case "caller":
                    console.log("[ROLE] Server assigned CALLER");
                    this.isCaller = true;
                    await this._createAndSendOffer();
                    break;

                case "receiver":
                    console.log("[ROLE] Server assigned RECEIVER");
                    this.isCaller = false;
                    break;

                case "offer":
                    console.log("[SIGNAL] Received OFFER");
                    await this._handleOffer(message.payload);
                    break;

                case "answer":
                    console.log("[SIGNAL] Received ANSWER");
                    if (this.peer.signalingState === "have-local-offer") {
                        await this.peer.setRemoteDescription(message.payload);
                        console.log("[SDP] Remote ANSWER set");
                        await this._flushPendingCandidates();
                    }
                    break;

                case "candidate":
                    if (this.peer.remoteDescription) {
                        await this.peer.addIceCandidate(message.payload);
                        console.log("[ICE] Added remote ICE candidate");
                    } else {
                        console.log("[ICE] Queueing ICE candidate");
                        this._pendingCandidates.push(message.payload);
                    }
                    break;
            }
        };

        this.socket.onclose = () => {
            console.log("[WS] WebSocket closed");
        };

        this.socket.onerror = (err) => {
            console.error("[WS] WebSocket error:", err);
            if (this.onerror) this.onerror(err);
        };
    }

    async _createAndSendOffer() {
        this.dataChannel = this.peer.createDataChannel("chat");
        this._setupDataChannel();

        const offer = await this.peer.createOffer();
        await this.peer.setLocalDescription(offer);

        this._send({ type: "offer", payload: offer });
        console.log("[OFFER] Offer sent");
    }

    async _handleOffer(offer) {
        await this.peer.setRemoteDescription(offer);
        console.log("[SDP] Remote OFFER set");

        const answer = await this.peer.createAnswer();
        await this.peer.setLocalDescription(answer);
        console.log("[ANSWER] Local answer created");

        await this._flushPendingCandidates();

        this._send({ type: "answer", payload: answer });
        console.log("[ANSWER] Answer sent to server");
    }

    _setupPeerEvents() {

        this.peer.onicecandidate = (event) => {
            if (event.candidate) {
                console.log("[ICE] Local ICE candidate generated");
                this._send({ type: "candidate", payload: event.candidate });
            } else {
                console.log("[ICE] ICE gathering complete");
            }
        };

        this.peer.onconnectionstatechange = () => {
            console.log("[PEER] Connection state:", this.peer.connectionState);
            if (this.peer.connectionState === "failed") {
                console.error("[PEER] Connection failed");
                if (this.onerror) this.onerror(new Error("Peer connection failed"));
            }
        };

        this.peer.oniceconnectionstatechange = () => {
            console.log("[ICE] ICE connection state:", this.peer.iceConnectionState);
        };

        this.peer.ondatachannel = (event) => {
            console.log("[DATA] DataChannel received by receiver");
            this.dataChannel = event.channel;
            this._setupDataChannel();
        };
    }

    _setupDataChannel() {
        this.dataChannel.onopen = () => {
            console.log("[DATA] DataChannel OPEN — ready!");
            if (this.onopen) this.onopen();
        };

        this.dataChannel.onmessage = (e) => {
            console.log("[DATA] Message received");
            if (this.onmessage) this.onmessage(e.data);
        };

        this.dataChannel.onclose = () => {
            console.log("[DATA] DataChannel closed");
            if (this.onclose) this.onclose();
        };

        this.dataChannel.onerror = (err) => {
            console.error("[DATA] DataChannel error:", err);
            if (this.onerror) this.onerror(err);
        };
    }

    async _flushPendingCandidates() {
        if (this._pendingCandidates.length === 0) return;
        console.log(`[ICE] Flushing ${this._pendingCandidates.length} queued candidate(s)`);
        for (const c of this._pendingCandidates) {
            try {
                await this.peer.addIceCandidate(c);
                console.log("[ICE] Queued candidate added");
            } catch (err) {
                console.error("[ICE] Failed to add queued candidate:", err);
            }
        }
        this._pendingCandidates = [];
    }

    _send(message) {
        const payload = this.optional !== null
            ? { ...message, optional: this.optional }
            : message;

        if (this.socket?.readyState === WebSocket.OPEN) {
            this.socket.send(JSON.stringify(payload));
        } else {
            console.warn("[WS] Socket not open — signal not sent:", message.type);
        }
    }

    send(message) {
        if (this.dataChannel?.readyState === "open") {
            this.dataChannel.send(message);
        } else {
            console.warn("[DATA] DataChannel not open — message dropped");
        }
    }

    close() {
        this.dataChannel?.close();
        this.peer.close();
        this.socket?.close();
    }
}