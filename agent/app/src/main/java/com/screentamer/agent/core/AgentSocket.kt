package com.screentamer.agent.core

import android.content.Context
import android.util.Log
import com.screentamer.agent.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client connecting to the parent server, with automatic
 * reconnect and exponential backoff.
 */
class AgentSocket(private val context: Context, private val listener: Listener) {

    companion object {
        private const val TAG = "ScreenTamer/AgentSocket"
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(type: String, payload: JSONObject)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null

    @Volatile
    private var stopped = false

    @Volatile
    var connected: Boolean = false
        private set

    private var reconnectDelayMs = 2000L
    private var attempt = 0

    fun start() {
        stopped = false
        attempt = 0
        connect()
    }

    fun stop() {
        stopped = true
        connected = false
        Log.i(TAG, "stopping relay socket")
        ws?.close(1000, "agent stopped")
        ws = null
    }

    fun send(type: String, payload: JSONObject = JSONObject()) {
        if (!connected) return
        val envelope = JSONObject(payload.toString()).put("type", type)
        Log.d(TAG, "sending $type")
        try {
            ws?.send(envelope.toString())
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    private fun connect() {
        val url = Prefs.serverUrl(context).trim()
        if (url.isBlank()) {
            Log.d(TAG, "relay url blank — reconnect deferred")
            scheduleReconnect()
            return
        }
        attempt++
        Log.i(TAG, "connect attempt #$attempt to $url")
        val ok = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        client = ok
        ws = ok.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    Log.i(TAG, "connected to relay (attempt #$attempt)")
                    attempt = 0
                    reconnectDelayMs = 2000L
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    Log.i(TAG, "relay closed: $code $reason")
                    listener.onDisconnected()
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "connection failure: ${t.message}${response?.let { " (code ${it.code})" } ?: ""}")
                    connected = false
                    listener.onDisconnected()
                    scheduleReconnect()
                }
            }
        )
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            val type = msg.optString("type")
            Log.d(TAG, "received $type")
            listener.onMessage(type, msg)
        } catch (e: Exception) {
            Log.w(TAG, "bad message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        Log.d(TAG, "reconnecting in ${reconnectDelayMs}ms")
        scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
            if (!stopped && Prefs.serverUrl(context).isNotBlank()) connect()
        }
    }
}
