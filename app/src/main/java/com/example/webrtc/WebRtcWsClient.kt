package com.example.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class WebRtcWsClient(
    context: Context,
    private val signalingUrl: String,
    rtcConfig: PeerConnection.RTCConfiguration = defaultRtcConfiguration(),
    private val optional: String? = null
) {

    var onMessage: ((String) -> Unit)? = null
    var onSignalingOpen: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    @Volatile
    var isCaller: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCandidates = CopyOnWriteArrayList<IceCandidate>()

    private val socketClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var webSocket: WebSocket? = null

    private var iceRestartAttempts = 0
    private val maxIceRestartAttempts = 2

    private val peerFactory: PeerConnectionFactory
    private val peer: PeerConnection
    private var dataChannel: DataChannel? = null

    init {
        initializeWebRtc(context.applicationContext)

        peerFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        peer = peerFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                log("[PEER] Signaling state: $newState")
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                log("[ICE] ICE connection state: $newState")
            }

            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                log("[ICE] Standardized ICE connection state: $newState")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                log("[PEER] Connection state: $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        iceRestartAttempts = 0
                    }

                    PeerConnection.PeerConnectionState.FAILED -> {
                        if (isCaller && iceRestartAttempts < maxIceRestartAttempts) {
                            iceRestartAttempts += 1
                            log("[ICE] Peer failed, attempting ICE restart ($iceRestartAttempts/$maxIceRestartAttempts)")
                            createAndSendOffer(iceRestart = true)
                        } else {
                            log("[ICE] P2P failed. Falling back to signaling relay mode")
                        }
                    }

                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                log("[ICE] ICE gathering state: $newState")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                log("[ICE] Local ICE candidate generated (type=${extractCandidateType(candidate.sdp)})")
                sendSignal("candidate", candidateToJson(candidate))
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onDataChannel(channel: DataChannel) {
                log("[DATA] DataChannel received by receiver")
                dataChannel = channel
                setupDataChannel(channel)
            }

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
        }) ?: throw IllegalStateException("Failed to create PeerConnection")
    }

    fun connect() {
        if (webSocket != null) return

        val request = Request.Builder().url(signalingUrl).build()

        webSocket = socketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("[WS] WebSocket connected")
                dispatchOnSignalingOpen()
                sendSignal("join")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                handleSignalingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("[WS] WebSocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("[WS] WebSocket closed: $code $reason")
                this@WebRtcWsClient.webSocket = null
                dispatchOnClose()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val responseSummary = response?.let { "HTTP ${it.code} ${it.message}" } ?: "No HTTP response"
                log("[WS] WebSocket error: ${t.message ?: t.javaClass.simpleName} ($responseSummary)")
                this@WebRtcWsClient.webSocket = null
                dispatchError(t)
                dispatchOnClose()
            }
        })
    }

    fun send(message: String) {
        val channel = dataChannel
        if (channel?.state() == DataChannel.State.OPEN) {
            val bytes = message.toByteArray(StandardCharsets.UTF_8)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            log("[DATA] Message sent via DataChannel")
        } else {
            val payload = JSONObject().put("text", message)
            sendSignal("chat", payload, extra = JSONObject().put("text", message))
        }
    }

    fun close() {
        try {
            dataChannel?.close()
            dataChannel?.dispose()
        } catch (_: Throwable) {
        }

        try {
            peer.close()
            peer.dispose()
        } catch (_: Throwable) {
        }

        try {
            webSocket?.close(1000, "Client closed")
            webSocket = null
        } catch (_: Throwable) {
        }

        dispatchOnClose()
    }

    private fun handleSignalingMessage(raw: String) {
        val message = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            dispatchError(IllegalArgumentException("Invalid signaling JSON: $raw", t))
            return
        }

        when (message.optString("type")) {
            "caller" -> {
                log("[ROLE] Server assigned CALLER")
                isCaller = true
                createAndSendOffer()
            }

            "receiver" -> {
                log("[ROLE] Server assigned RECEIVER")
                isCaller = false
            }

            "offer" -> {
                log("[SIGNAL] Received OFFER")
                val payload = message.optJSONObject("payload") ?: return
                handleOffer(payload)
            }

            "answer" -> {
                log("[SIGNAL] Received ANSWER")
                val payload = message.optJSONObject("payload") ?: return
                val answer = sessionDescriptionFromJson(payload) ?: return

                if (peer.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    setRemoteDescription(answer,
                        onSuccess = {
                            log("[SDP] Remote ANSWER set")
                            flushPendingCandidates()
                        },
                        onFailure = { reason ->
                            dispatchError(IllegalStateException("Failed setting remote answer: $reason"))
                        }
                    )
                }
            }

            "candidate" -> {
                val payload = message.optJSONObject("payload") ?: return
                val candidate = iceCandidateFromJson(payload) ?: return

                if (peer.remoteDescription != null) {
                    val added = peer.addIceCandidate(candidate)
                    if (added) {
                        log("[ICE] Added remote ICE candidate (type=${extractCandidateType(candidate.sdp)})")
                    } else {
                        log("[ICE] Failed to add remote ICE candidate")
                    }
                } else {
                    log("[ICE] Queueing ICE candidate (type=${extractCandidateType(candidate.sdp)})")
                    pendingCandidates.add(candidate)
                }
            }

            "chat" -> {
                val payloadText = message.optJSONObject("payload")?.optString("text", "") ?: ""
                val topLevelText = message.optString("text", "")
                val text = if (payloadText.isNotBlank()) payloadText else topLevelText
                if (text.isNotBlank()) {
                    log("[WS] Relay message received")
                    mainHandler.post { onMessage?.invoke(text) }
                }
            }

            "" -> {
                val topLevelText = message.optString("text", "")
                if (topLevelText.isNotBlank()) {
                    log("[WS] Relay message received (legacy format)")
                    mainHandler.post { onMessage?.invoke(topLevelText) }
                }
            }

            else -> {
                log("[SIGNAL] Ignored unknown message: $raw")
            }
        }
    }

    private fun createAndSendOffer(iceRestart: Boolean = false) {
        if (dataChannel == null) {
            val init = DataChannel.Init()
            val channel = peer.createDataChannel("chat", init)
            dataChannel = channel
            setupDataChannel(channel)
        }

        val constraints = MediaConstraints().apply {
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }

        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                val offer = desc ?: return
                setLocalDescription(offer,
                    onSuccess = {
                        sendSignal("offer", sessionDescriptionToJson(offer))
                        if (iceRestart) {
                            log("[OFFER] ICE restart offer sent")
                        } else {
                            log("[OFFER] Offer sent")
                        }
                    },
                    onFailure = { reason ->
                        dispatchError(IllegalStateException("Failed setting local offer: $reason"))
                    }
                )
            }

            override fun onSetSuccess() = Unit

            override fun onCreateFailure(reason: String?) {
                dispatchError(IllegalStateException("Failed creating offer: $reason"))
            }

            override fun onSetFailure(reason: String?) = Unit
        }, constraints)
    }

    private fun handleOffer(offerJson: JSONObject) {
        val offer = sessionDescriptionFromJson(offerJson) ?: return

        setRemoteDescription(offer,
            onSuccess = {
                log("[SDP] Remote OFFER set")
                peer.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        val answer = desc ?: return
                        setLocalDescription(answer,
                            onSuccess = {
                                log("[ANSWER] Local answer created")
                                flushPendingCandidates()
                                sendSignal("answer", sessionDescriptionToJson(answer))
                                log("[ANSWER] Answer sent to server")
                            },
                            onFailure = { reason ->
                                dispatchError(IllegalStateException("Failed setting local answer: $reason"))
                            }
                        )
                    }

                    override fun onSetSuccess() = Unit

                    override fun onCreateFailure(reason: String?) {
                        dispatchError(IllegalStateException("Failed creating answer: $reason"))
                    }

                    override fun onSetFailure(reason: String?) = Unit
                }, MediaConstraints())
            },
            onFailure = { reason ->
                dispatchError(IllegalStateException("Failed setting remote offer: $reason"))
            }
        )
    }

    private fun setupDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val state = channel.state()
                log("[DATA] DataChannel state: $state")
                when (state) {
                    DataChannel.State.OPEN -> dispatchOnOpen()
                    DataChannel.State.CLOSED -> {
                        if (webSocket == null) {
                            dispatchOnClose()
                        } else {
                            log("[DATA] DataChannel closed, signaling relay still available")
                        }
                    }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                val text = String(bytes, StandardCharsets.UTF_8)
                log("[DATA] Message received")
                mainHandler.post { onMessage?.invoke(text) }
            }
        })
    }

    private fun flushPendingCandidates() {
        if (pendingCandidates.isEmpty()) return

        log("[ICE] Flushing ${pendingCandidates.size} queued candidate(s)")
        val iterator = pendingCandidates.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            val added = peer.addIceCandidate(candidate)
            if (added) {
                log("[ICE] Queued candidate added")
            } else {
                log("[ICE] Failed to add queued candidate")
            }
            pendingCandidates.remove(candidate)
        }
    }

    private fun sendSignal(type: String, payload: JSONObject? = null, extra: JSONObject? = null) {
        val message = JSONObject().put("type", type)

        if (payload != null) {
            message.put("payload", payload)
        }

        if (extra != null) {
            extra.keys().forEach { key ->
                message.put(key, extra.get(key))
            }
        }

        if (!optional.isNullOrBlank()) {
            message.put("optional", optional)
        }

        val sent = webSocket?.send(message.toString()) == true
        if (!sent) {
            log("[WS] Socket not open — signal not sent: $type")
        }
    }

    private fun setLocalDescription(
        sdp: SessionDescription,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit
    ) {
        peer.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) = Unit

            override fun onSetSuccess() = onSuccess()

            override fun onCreateFailure(reason: String?) = Unit

            override fun onSetFailure(reason: String?) = onFailure(reason)
        }, sdp)
    }

    private fun setRemoteDescription(
        sdp: SessionDescription,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit
    ) {
        peer.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) = Unit

            override fun onSetSuccess() = onSuccess()

            override fun onCreateFailure(reason: String?) = Unit

            override fun onSetFailure(reason: String?) = onFailure(reason)
        }, sdp)
    }

    private fun sessionDescriptionToJson(sdp: SessionDescription): JSONObject {
        return JSONObject()
            .put("type", sdp.type.canonicalForm())
            .put("sdp", sdp.description)
    }

    private fun sessionDescriptionFromJson(json: JSONObject): SessionDescription? {
        val type = when (json.optString("type").lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            "rollback" -> SessionDescription.Type.ROLLBACK
            else -> null
        } ?: return null

        val sdp = json.optString("sdp", "")
        if (sdp.isBlank()) return null

        return SessionDescription(type, sdp)
    }

    private fun candidateToJson(candidate: IceCandidate): JSONObject {
        return JSONObject()
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
    }

    private fun iceCandidateFromJson(json: JSONObject): IceCandidate? {
        val candidate = json.opt("candidate") as? String ?: return null
        val sdpMLineIndex = json.optInt("sdpMLineIndex", -1)
        if (sdpMLineIndex < 0) return null

        val sdpMid = json.opt("sdpMid") as? String
        return IceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    private fun dispatchOnOpen() {
        mainHandler.post { onOpen?.invoke() }
    }

    private fun dispatchOnSignalingOpen() {
        mainHandler.post { onSignalingOpen?.invoke() }
    }

    private fun dispatchOnClose() {
        mainHandler.post { onClose?.invoke() }
    }

    private fun dispatchError(t: Throwable) {
        mainHandler.post { onError?.invoke(t) }
    }

    private fun log(message: String) {
        mainHandler.post { onLog?.invoke(message) }
    }

    private fun extractCandidateType(candidateSdp: String?): String {
        if (candidateSdp.isNullOrBlank()) return "unknown"
        val marker = " typ "
        val start = candidateSdp.indexOf(marker)
        if (start == -1) return "unknown"
        val typeStart = start + marker.length
        val end = candidateSdp.indexOf(' ', typeStart)
        return if (end == -1) candidateSdp.substring(typeStart) else candidateSdp.substring(typeStart, end)
    }

    companion object {
        @Volatile
        private var initialized = false

        fun defaultRtcConfiguration(): PeerConnection.RTCConfiguration {
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80?transport=udp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80?transport=tcp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443?transport=udp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443?transport=tcp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer()
            )
            return PeerConnection.RTCConfiguration(iceServers).apply {
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }
        }

        @Synchronized
        private fun initializeWebRtc(context: Context) {
            if (initialized) return

            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()

            PeerConnectionFactory.initialize(options)
            initialized = true
        }
    }
}
