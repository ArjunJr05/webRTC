import { WebRTCWebSocket } from "./Webrtcwebsocket.js";

const chatDiv = document.getElementById("chat");
const input = document.getElementById("messageInput");
const sendBtn = document.getElementById("sendBtn");

function addMessage(text, self = false) {
    const p = document.createElement("p");
    p.textContent = (self ? "You: " : "Peer: ") + text;
    chatDiv.appendChild(p);
    chatDiv.scrollTop = chatDiv.scrollHeight;
}

const rtc = new WebRTCWebSocket({
    signalingUrl: "ws://localhost:8080/ws",
    rtcConfig: {
        iceServers: [
            {
                urls: "stun:stun.l.google.com:19302"
            }
        ]
    },
    optional : {}
});

rtc.onopen = () => {
    addMessage("Connected!", true);
};

rtc.onmessage = (msg) => {
    addMessage(msg);
};

rtc.onerror = (err) => {
    console.error(err);
};

rtc.connect();

sendBtn.onclick = () => {
    const text = input.value.trim();
    if (!text) return;

    rtc.send(text);
    addMessage(text, true);
    input.value = "";
};