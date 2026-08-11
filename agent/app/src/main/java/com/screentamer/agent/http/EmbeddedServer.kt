package com.screentamer.agent.http

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Minimal HTTP/1.1 server (no dependencies) that serves the parent dashboard
 * and the same REST API the relay server exposes. Each request gets its own
 * thread; the body is read up front, so handlers can be blocking.
 */
class EmbeddedServer(
    private val port: Int,
    private val handler: Handler,
) {
    interface Handler {
        fun login(password: String): Boolean
        fun state(): JSONObject
        fun history(days: Int): JSONObject
        fun config(policy: JSONObject): String?
        fun command(type: String, pkg: String?): String?
        /** Real app icon (PNG bytes) for a package, or null when unknown. */
        fun icon(pkg: String): ByteArray?
        fun assets(): Assets
    }

    interface Assets {
        fun open(path: String): InputStream?
        fun mime(name: String): String
    }

    private val logTag = "ScreenTamer/EmbeddedServer"
    private var serverSocket: ServerSocket? = null
    private val activeThreads = mutableSetOf<Thread>()
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                Log.i(logTag, "listening on :$port")
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    val t = Thread { handle(client) }
                    t.start()
                    synchronized(activeThreads) { activeThreads.add(t) }
                }
            } catch (e: Exception) {
                if (running) Log.e(logTag, "server failed", e)
            } finally {
                running = false
            }
        }.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        synchronized(activeThreads) {
            activeThreads.forEach { t ->
                try {
                    t.join(1000)
                } catch (e: Exception) {
                }
            }
            activeThreads.clear()
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]

                var contentLength = 0
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (line != null && line.isNotBlank()) {
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    line = reader.readLine()
                }
                contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val (path, query) = rawPath.split('?', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
                respond(c, method, path, query, headers, body)
            }
        } catch (e: Exception) {
            Log.w(logTag, "request failed: ${e.message}")
        }
    }

    private fun respond(c: Socket, method: String, path: String, query: String, headers: Map<String, String>, body: String) {
        val started = System.currentTimeMillis()
        val out = c.getOutputStream()
        val pathLower = path.lowercase()
        var status = 200
        fun json(code: Int, obj: JSONObject) {
            status = code
            sendJson(out, code, obj)
        }
        fun asset(name: String) {
            val served = serveAsset(out, name)
            if (!served) status = 404
        }

        // Static dashboard (path maps 1:1 under assets/www/, subdirs preserved)
        if (method == "GET" && (path == "/" || path == "/index.html")) asset("index.html")
        else if (method == "GET" && pathLower.startsWith("/static/")) asset(path.substringAfter("/static/"))

        // Browsers ask for /favicon.ico by default; point them at the SVG like the relay does
        else if (method == "GET" && path == "/favicon.ico") {
            out.write("HTTP/1.1 302 Found\r\nLocation: /static/favicon.svg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        }

        // REST API
        else when {
            method == "GET" && path == "/api/icon" -> {
                val pkg = query.substringAfter("pkg=", "").substringBefore('&').trim()
                if (pkg.isEmpty()) return json(400, JSONObject().put("ok", false).put("error", "pkg required"))
                val bytes = handler.icon(pkg)
                if (bytes == null) json(404, JSONObject().put("ok", false).put("error", "unknown package"))
                else {
                    status = 200
                    sendBytes(out, bytes, "image/png")
                }
            }
            method == "POST" && path == "/api/login" -> {
                val pw = bodyJson(body).optString("password")
                if (handler.login(pw)) json(200, JSONObject().put("ok", true))
                else json(401, JSONObject().put("ok", false).put("error", "wrong password"))
            }
            method == "GET" && path == "/api/state" -> {
                if (!authed(query, headers)) return json(401, JSONObject().put("ok", false).put("error", "unauthorized"))
                json(200, handler.state())
            }
            method == "GET" && path == "/api/history" -> {
                if (!authed(query, headers)) return json(401, JSONObject().put("ok", false).put("error", "unauthorized"))
                val days = (query.substringAfter("days=").substringBefore('&').toIntOrNull() ?: 14).coerceIn(1, 365)
                json(200, handler.history(days))
            }
            method == "POST" && path == "/api/config" -> {
                if (!authed(query, headers, bodyJson(body))) return json(401, JSONObject().put("ok", false).put("error", "unauthorized"))
                val err = handler.config(bodyJson(body).optJSONObject("policy") ?: JSONObject())
                if (err == null) json(200, JSONObject().put("ok", true))
                else json(400, JSONObject().put("ok", false).put("error", err))
            }
            method == "POST" && path == "/api/command" -> {
                if (!authed(query, headers, bodyJson(body))) return json(401, JSONObject().put("ok", false).put("error", "unauthorized"))
                val cmd = bodyJson(body).optJSONObject("command")
                val err = handler.command(cmd?.optString("type", "") ?: "", cmd?.optString("pkg")?.ifBlank { null })
                if (err == null) json(200, JSONObject().put("ok", true).put("delivered", true))
                else json(400, JSONObject().put("ok", false).put("error", err))
            }
            else -> json(404, JSONObject().put("ok", false).put("error", "not found"))
        }
        Log.i(logTag, "[http] ${c.inetAddress?.hostAddress ?: "?"} $method $path -> $status (${System.currentTimeMillis() - started}ms)")
    }

    private fun authed(query: String, headers: Map<String, String>, body: JSONObject = JSONObject()): Boolean {
        val pw = body.optString("password").ifBlank {
            query.substringAfter("password=", "").substringBefore('&')
        }.ifBlank {
            headers["x-parent-password"] ?: ""
        }
        return handler.login(pw)
    }

    private fun bodyJson(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: Exception) {
        JSONObject()
    }

    private fun serveAsset(out: java.io.OutputStream, name: String): Boolean {
        val assets = handler.assets()
        val input = assets.open(name)
        if (input == null) {
            sendJson(out, 404, JSONObject().put("ok", false).put("error", "not found"))
            return false
        }
        input.use { it ->
            val data = it.readBytes()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: ${assets.mime(name)}\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            out.write(data)
        }
        return true
    }

    private fun sendBytes(out: java.io.OutputStream, data: ByteArray, contentType: String) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${data.size}\r\nCache-Control: public, max-age=86400\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        out.write(data)
    }

    private fun sendJson(out: java.io.OutputStream, code: Int, obj: JSONObject) {
        val body = obj.toString().toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        out.write(body)
    }
}
