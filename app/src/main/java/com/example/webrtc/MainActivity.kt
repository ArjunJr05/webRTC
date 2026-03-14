package com.example.webrtc

import android.os.Bundle
import android.os.Build
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat

import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var rtcClient: WebRtcWsClient? = null

    private lateinit var etSignalingUrl: EditText
    private lateinit var etOptional: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnDeleteLogs: Button
    private lateinit var tvLogs: TextView

    private val timestampFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSignalingUrl = findViewById(R.id.etSignalingUrl)
        etOptional = findViewById(R.id.etOptional)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus = findViewById(R.id.tvStatus)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnDeleteLogs = findViewById(R.id.btnDeleteLogs)
        tvLogs = findViewById(R.id.tvLogs)

        etSignalingUrl.setText(getString(R.string.default_signaling_url))

        btnConnect.setOnClickListener { connect() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnSend.setOnClickListener { sendMessage() }
        btnDeleteLogs.setOnClickListener { clearLogs() }

        setUiConnected(false)
        setStatus(getString(R.string.status_disconnected))
    }

    private fun connect() {
        if (rtcClient != null) return

        val signalingUrl = etSignalingUrl.text.toString().trim()
        if (signalingUrl.isBlank()) {
            Toast.makeText(this, R.string.error_signaling_url_required, Toast.LENGTH_SHORT).show()
            return
        }

        if (!isRunningOnEmulator() && signalingUrl.contains("10.0.2.2")) {
            Toast.makeText(this, R.string.error_phone_host_hint, Toast.LENGTH_LONG).show()
            appendLog("[APP] 10.0.2.2 works only on emulator. Use your PC LAN IP (example: ws://192.168.1.10:8080/ws)")
            setStatus(getString(R.string.status_error))
            return
        }

        val optional = etOptional.text.toString().trim().takeIf { it.isNotEmpty() }

        val client = WebRtcWsClient(
            context = applicationContext,
            signalingUrl = signalingUrl,
            optional = optional
        )

        client.onLog = { appendLog(it) }
        client.onSignalingOpen = {
            setStatus(getString(R.string.status_connected))
            btnSend.isEnabled = true
            appendLog("[APP] Signaling channel ready")
        }
        client.onOpen = {
            setStatus(getString(R.string.status_connected))
            btnSend.isEnabled = true
            appendLog("[APP] Data channel ready")
        }
        client.onMessage = {
            appendLog("[PEER] $it")
        }
        client.onClose = {
            setStatus(getString(R.string.status_disconnected))
            setUiConnected(false)
            rtcClient = null
            appendLog("[APP] Connection closed")
        }
        client.onError = {
            appendLog("[ERROR] ${it.message ?: it.javaClass.simpleName}")
            setStatus(getString(R.string.status_error))
        }

        rtcClient = client
        setUiConnected(true)
        btnSend.isEnabled = false
        setStatus(getString(R.string.status_connecting))
        appendLog("[APP] Connecting to $signalingUrl")
        client.connect()
    }

    private fun disconnect() {
        rtcClient?.close()
        rtcClient = null
        setUiConnected(false)
        setStatus(getString(R.string.status_disconnected))
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isBlank()) return

        val client = rtcClient
        if (client == null) {
            Toast.makeText(this, R.string.error_not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        client.send(text)
        appendLog("[YOU] $text")
        etMessage.text.clear()
    }

    private fun setUiConnected(connected: Boolean) {
        btnConnect.isEnabled = !connected
        btnDisconnect.isEnabled = connected
        etSignalingUrl.isEnabled = !connected
        etOptional.isEnabled = !connected
        btnSend.isEnabled = false
    }

    private fun setStatus(status: String) {
        tvStatus.text = getString(R.string.status_prefix, status)
    }

    private fun appendLog(message: String) {
        val timestamp = timestampFormatter.format(Date())
        tvLogs.append("[$timestamp] $message\n")
    }

    private fun clearLogs() {
        tvLogs.text = getString(R.string.logs_title)
    }

    override fun onDestroy() {
        super.onDestroy()
        rtcClient?.close()
        rtcClient = null
    }

    private fun isRunningOnEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.lowercase(Locale.US).contains("emulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.BRAND.startsWith("generic") ||
            Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT.contains("sdk")
    }
}