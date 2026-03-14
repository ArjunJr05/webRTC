export class WebRTCHttp {

    constructor({ baseURL, rtcConfig = {}, pollInterval = 1000 }) {

        this.baseURL = baseURL;

        this.pollInterval = pollInterval;
        this._pendingCandidates = [];

        this.peer = new RTCPeerConnection(rtcConfig);
        this.dataChannel = null;

        this._candidateTimer = null;
        this._answerTimer = null;
        this._offerTimer = null;
        this.isCaller = false;

        this.onmessage = null;
        this.onopen = null;
        this.onclose = null;
        this.onerror = null;

        this._setupPeerEvents();
    }

    async connect() {
        try {
            const res = await fetch(this.baseURL + `/signal/check`, { method: "POST" });
            const data = await res.json();
            this.isCaller = data?.caller === true;

            if (this.isCaller) {

                console.log("Became caller");
                await this._createAndSendOffer();

            } else {

                console.log("Became receiver");
                this._startOfferPolling();
            }
            this._startCandidatePolling();

        } catch (err) {
            console.error("Connection error:", err);
            if (this.onerror) this.onerror(err);
        }
    }

    async _createAndSendOffer() {
        this.dataChannel = this.peer.createDataChannel("chat");
        this._setupDataChannel();

        const offer = await this.peer.createOffer();
        await this.peer.setLocalDescription(offer);

        await this._post(`/signal/offer`, offer);

        this._startAnswerPolling();
    }

    _startOfferPolling() {
        if (this._offerTimer) return;

        this._offerTimer = setInterval(async () => {
            try {
                const offer = await this._get(`/signal/offer`);

                if (!offer) return;

                console.log("Offer received, becoming receiver");

                clearInterval(this._offerTimer);
                this._offerTimer = null;

                await this._handleOffer(offer);

            } catch (err) {
                console.error("Offer polling error:", err);
            }
        }, this.pollInterval);
    }

    async _handleOffer(offer) {
        await this.peer.setRemoteDescription(offer);
        const answer = await this.peer.createAnswer();
        await this.peer.setLocalDescription(answer);
        await this._flushPendingCandidates();
        await this._post(`/signal/answer`, answer);
        console.log("Answer sent");
    }

    _startAnswerPolling() {
        if (!this.isCaller) return;
        if (this._answerTimer) return;

        this._answerTimer = setInterval(async () => {
            try {
                const answer = await this._get(`/signal/answer`);

                if (answer) {
                    if (this.peer.signalingState === "have-local-offer") {
                        await this.peer.setRemoteDescription(answer);
                        await this._flushPendingCandidates();
                        console.log("Received answer, connection should establish soon...");

                        clearInterval(this._answerTimer);
                        this._answerTimer = null;
                    }
                }
            } catch (err) {
                console.error("Answer polling error:", err);
            }
        }, this.pollInterval);
    }

    _startCandidatePolling() {
        if (this._candidateTimer) return;

        this._candidateTimer = setInterval(async () => {
            try {
                const candidates = await this._get_candidates(
                    `/signal/candidates/${this.isCaller}`
                );

                if (Array.isArray(candidates) && candidates.length > 0) {
                    for (const c of candidates) {
                        if (this.peer.remoteDescription) {
                            await this.peer.addIceCandidate(c);
                            console.log("Added remote candidate");
                        } else {
                            this._pendingCandidates.push(c);
                        }
                    }
                }
            } catch (err) {
                console.error("Candidate polling error:", err);
            }
        }, this.pollInterval);
    }

    _setupPeerEvents() {

        this.peer.onconnectionstatechange = () => {
            if (this.peer.connectionState === "connected") {

                if (this._candidateTimer) {
                    clearInterval(this._candidateTimer);
                    this._candidateTimer = null;
                }
                console.log("Peer fully connected");
            }
        };

        this.peer.onicecandidate = async (event) => {
            if (event.candidate) {
                try {
                    await this._post(
                        `/signal/candidates/${this.isCaller}`,
                        event.candidate
                    );
                    console.log("New ICE candidate sent to server");
                } catch (err) {
                    console.error("Failed to send ICE candidate:", err);
                    if (this.onerror) this.onerror(err);
                }
            }
        };

        this.peer.ondatachannel = (event) => {
            this.dataChannel = event.channel;
            this._setupDataChannel();
        };
    }

    _setupDataChannel() {
        this.dataChannel.onopen = () => {
            console.log("DataChannel open");
            if (this.onopen) this.onopen();
        };

        this.dataChannel.onmessage = (e) => {
            console.log("Received:", e.data);
            if (this.onmessage) this.onmessage(e.data);
        };

        this.dataChannel.onclose = () => {
            console.log("DataChannel closed");
            if (this.onclose) this.onclose();
        };

        this.dataChannel.onerror = (err) => {
            console.error("DataChannel error:", err);
            if (this.onerror) this.onerror(err);
        };
    }
    
    async _get(path) {
        try {
            const res = await fetch(this.baseURL + path);

            if (res.status === 204) return null;
            if (!res.ok) return null;

            return await res.json();

        } catch (err) {
            console.error(`GET ${path} failed:`, err);
            return null;
        }
    }

    async _get_candidates(path) {
        try {
            const res = await fetch(this.baseURL + path);

            if (res.status === 204) return [];
            if (!res.ok) return [];

            return await res.json();

        } catch (err) {
            console.error(`GET candidates ${path} failed:`, err);
            return [];
        }
    }

    async _post(path, body) {
        try {
            const res = await fetch(this.baseURL + path, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            if (res.status === 204) return null;
            if (!res.ok) return null;
            const contentType = res.headers.get("content-type");
            if (contentType && contentType.includes("application/json")) {
                return await res.json();
            }

            return null;

        } catch (err) {
            console.error(`POST ${path} failed:`, err);
            throw err;
        }
    }

    send(message) {
        if (this.dataChannel && this.dataChannel.readyState === "open") {
            this.dataChannel.send(message);
        } else {
            console.warn("DataChannel is not open. Message not sent.");
        }
    }

    close() {
        if (this._answerTimer) {
            clearInterval(this._answerTimer);
            this._answerTimer = null;
        }

        if (this._candidateTimer) {
            clearInterval(this._candidateTimer);
            this._candidateTimer = null;
        }

        if (this._offerTimer) {
            clearInterval(this._offerTimer);
            this._offerTimer = null;
        }

        this._pendingCandidates = [];

        this.dataChannel?.close();
        this.peer.close();
    }

    async _flushPendingCandidates() {
        if (!this.peer.remoteDescription) return;

        for (const c of this._pendingCandidates) {
            try {
                await this.peer.addIceCandidate(c);
                console.log("Flushed queued candidate");
            } catch (err) {
                console.error("Failed to flush candidate:", err);
            }
        }

        this._pendingCandidates = [];
    }
}