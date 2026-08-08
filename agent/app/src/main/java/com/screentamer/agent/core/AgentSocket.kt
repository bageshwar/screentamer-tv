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

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        connected = false
        ws?.close(1000, "agent stopped")
        ws = null
    }

    fun send(type: String, payload: JSONObject = JSONObject()) {
        if (!connected) return
        val envelope = JSONObject(payload.toString()).put("type", type)
        Log.d("AgentSocket", "sending $type")
        try {
            ws?.send(envelope.toString())
        } catch (e: Exception) {
            Log.w("AgentSocket", "send failed: ${e.message}")
        }
    }

    private fun connect() {
        val url = Prefs.serverUrl(context).trim()
        if (url.isBlank()) {
            scheduleReconnect()
            return
        }
        Log.i("AgentSocket", "connecting to $url")
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
                    reconnectDelayMs = 2000L
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    listener.onDisconnected()
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w("AgentSocket", "connection failure: ${t.message}")
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
            listener.onMessage(type, msg)
        } catch (e: Exception) {
            Log.w("AgentSocket", "bad message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
            if (!stopped && Prefs.serverUrl(context).isNotBlank()) connect()
        }
    }
}
