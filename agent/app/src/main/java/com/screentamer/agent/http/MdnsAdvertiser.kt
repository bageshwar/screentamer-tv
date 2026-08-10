package com.screentamer.agent.http

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.screentamer.agent.Prefs
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Local DNS broadcaster (mDNS/DNS-SD) for the embedded dashboard server.
 *
 * Unlike Android's NsdManager — which advertises the *system's* hostname and
 * cannot publish a friendly, app-chosen `.local` name — this responder speaks
 * the mDNS wire protocol directly on the multicast group `224.0.0.251:5353` and
 * owns its records:
 *
 *  - `PTR  <hostname>._http._tcp.local.`  (service discovery / browsing)
 *  - `SRV  <instance>._http._tcp.local.`  -> <hostname>.local:<port>
 *  - `A    <hostname>.local.`             -> the device's LAN IPv4 address
 *  - `TXT  <instance>._http._tcp.local.`  -> path=/
 *
 * The `<hostname>` is derived from the configured device name, so a parent can
 * open `http://<hostname>.local:<port>/` from any mDNS-capable device on the
 * LAN without knowing (or hunting for) the TV's IP address. This is what the
 * issue calls "broadcast a DNS".
 *
 * The responder answers queries on demand and re-announces every 30s so the
 * records stay cached (mDNS TTL is 120s).
 */
class MdnsAdvertiser(context: Context) {

    private val logTag = "ScreenTamer/MdnsAdvertiser"
    private val appContext = context.applicationContext

    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var respondThread: Thread? = null
    private var announceThread: Thread? = null

    @Volatile
    private var running = false
    private var port = 0
    private var instanceName = "screentamer"

    private val ipAddress: String? by lazy { findIpv4() }

    companion object {
        private const val MDNS_GROUP = "224.0.0.251"
        private const val MDNS_PORT = 5353
        private const val TYPE_PTR = 12
        private const val TYPE_TXT = 16
        private const val TYPE_SRV = 33
        private const val TYPE_A = 1
        private const val TYPE_ANY = 255
        private const val CLASS_IN = 1
        private const val TTL = 120L
        private const val ANNOUNCE_INTERVAL_MS = 30_000L
        private const val MAX_LABEL = 63
    }

    /**
     * The `.local` hostname this device broadcasts (e.g. `screentamer`),
     * derived from the configured device name and DNS-safe. Safe to call
     * before/after [start].
     */
    fun hostname(): String = sanitizeHost(Prefs.deviceName(appContext))

    /** Registers the dashboard service on [port] and starts answering mDNS. */
    fun start(port: Int, serviceName: String) {
        if (running) return
        this.port = port
        this.instanceName = serviceName
        val ip = ipAddress
        Log.i(logTag, "start: hostname=${hostname()}.local ip=$ip port=$port instance=$instanceName")
        if (ip == null) {
            Log.w(logTag, "no IPv4 address found — mDNS unavailable (dashboard still reachable by IP)")
            return
        }

        val sock = MulticastSocket(null)
        try {
            sock.reuseAddress = true
            sock.bind(java.net.InetSocketAddress(MDNS_PORT))
            val group = InetAddress.getByName(MDNS_GROUP)
            val iface = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
                .firstOrNull { ni ->
                    ni.isUp && !ni.isLoopback &&
                        java.util.Collections.list(ni.inetAddresses).any { it is Inet4Address && !it.isLoopbackAddress }
                }
            if (iface != null) {
                sock.setNetworkInterface(iface)
                sock.joinGroup(java.net.InetSocketAddress(InetAddress.getByName(MDNS_GROUP), 0), iface)
                Log.i(logTag, "joined $MDNS_GROUP on ${iface.name} (${iface.displayName})")
            } else {
                sock.joinGroup(group)
            }
            sock.timeToLive = 255
        } catch (e: Exception) {
            Log.w(logTag, "mDNS bind/join failed: ${e.message}", e)
            return
        }
        socket = sock

        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("screentamer-mdns")?.apply {
            setReferenceCounted(false)
            try {
                acquire()
            } catch (e: Exception) {
                Log.w(logTag, "multicast lock acquire failed: ${e.message}")
            }
        }

        running = true
        respondThread = thread(name = "mdns-respond", isDaemon = true) { respondLoop(sock) }
        announceThread = thread(name = "mdns-announce", isDaemon = true) { announceLoop(sock) }
        thread(name = "mdns-initial-announce", isDaemon = true) { announce(sock) }
        Log.i(logTag, "mDNS broadcasting http://${hostname()}.local:$port")
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            socket?.leaveGroup(InetAddress.getByName(MDNS_GROUP))
        } catch (e: Exception) {
            Log.w(logTag, "leaveGroup: ${e.message}")
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(logTag, "close: ${e.message}")
        }
        socket = null
        multicastLock?.let {
            try {
                it.release()
            } catch (e: Exception) {
                Log.w(logTag, "multicast lock release failed: ${e.message}")
            }
        }
        multicastLock = null
        respondThread?.join(500)
        announceThread?.join(500)
        respondThread = null
        announceThread = null
        Log.i(logTag, "mDNS stopped")
    }

    // ------------------------------------------------------------------
    // Responder
    // ------------------------------------------------------------------

    private fun respondLoop(sock: MulticastSocket) {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val response = buildResponse(buf, pkt.length) ?: continue
                val target = if (pkt.port == MDNS_PORT) {
                    InetAddress.getByName(MDNS_GROUP)
                } else {
                    pkt.address
                }
                sock.send(DatagramPacket(response, response.size, target, MDNS_PORT))
                Log.d(logTag, "answered ${pkt.length}B query with ${response.size}B (${buildResponseSummary(buf, pkt.length)})")
            } catch (e: Exception) {
                if (running) Log.w(logTag, "respond loop: ${e.message}")
            }
        }
    }

    private fun announceLoop(sock: MulticastSocket) {
        while (running) {
            Thread.sleep(ANNOUNCE_INTERVAL_MS)
            if (running) announce(sock)
        }
    }

    /** Sends an unsolicited announcement (PTR + SRV + A + TXT) to the group. */
    private fun announce(sock: MulticastSocket) {
        val records = mutableListOf<ByteArray>()
        ptrRecord()?.let(records::add)
        srvRecord()?.let(records::add)
        aRecord()?.let(records::add)
        txtRecord()?.let(records::add)
        if (records.isEmpty()) return
        val body = ByteArrayOutputStream()
        records.forEach(body::write)
        val out = ByteArrayOutputStream()
        writeHeader(out, answers = records.size)
        out.write(body.toByteArray())
        try {
            val group = InetAddress.getByName(MDNS_GROUP)
            sock.send(DatagramPacket(out.toByteArray(), out.size(), group, MDNS_PORT))
        } catch (e: Exception) {
            if (running) Log.w(logTag, "announce failed: ${e.message}", e)
        }
    }

    /**
     * Parses an mDNS query and returns a response packet, or null when nothing
     * in the query matches this advertiser.
     */
    private fun buildResponse(data: ByteArray, length: Int): ByteArray? {
        if (length < 12) return null
        val qd = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (qd == 0) return null // announcement, not a query
        val isResponse = (data[2].toInt() and 0x80) != 0
        if (isResponse) return null

        var pos = 12
        val questions = mutableListOf<Pair<String, Int>>()
        repeat(qd) {
            val (name, next) = readName(data, pos) ?: return null
            pos = next
            if (pos + 4 > length) return null
            val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val cls = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            questions.add(name to type)
            pos += 4
        }

        val answerNames = mutableSetOf<String>()
        questions.forEach { (qname, qtype) ->
            val matched = qtype == TYPE_ANY ||
                (qname == "_http._tcp.local." && qtype == TYPE_PTR) ||
                (qname == serviceInstance() && (qtype == TYPE_SRV || qtype == TYPE_TXT)) ||
                (qname == hostname().lowercase() + ".local." && qtype == TYPE_A)
            if (matched) {
                if (qname == "_http._tcp.local.") answerNames.add("ptr")
                if (qname == serviceInstance()) answerNames.add("instance")
                if (qname == hostname().lowercase() + ".local.") answerNames.add("host")
                if (qtype == TYPE_ANY) { answerNames.add("ptr"); answerNames.add("instance"); answerNames.add("host") }
            }
        }
        if (answerNames.isEmpty()) return null

        val records = mutableListOf<ByteArray>()
        if ("ptr" in answerNames) ptrRecord()?.let(records::add)
        if ("instance" in answerNames) {
            srvRecord()?.let(records::add)
            txtRecord()?.let(records::add)
        }
        if ("host" in answerNames) aRecord()?.let(records::add)
        if (records.isEmpty()) return null

        val body = ByteArrayOutputStream()
        records.forEach(body::write)
        val out = ByteArrayOutputStream()
        writeHeader(out, answers = records.size)
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    private fun serviceInstance(): String = "$instanceName._http._tcp.local."

    private fun ptrRecord(): ByteArray? {
        val rdata = encodeName(serviceInstance()) ?: return null
        return record("_http._tcp.local.", TYPE_PTR, CLASS_IN, TTL, rdata)
    }

    private fun srvRecord(): ByteArray? {
        val host = hostname().lowercase()
        val body = ByteArrayOutputStream()
        writeU16(body, 0) // priority
        writeU16(body, 0) // weight
        writeU16(body, port)
        body.write(encodeName("$host.local.") ?: return null)
        return record(serviceInstance(), TYPE_SRV, CLASS_IN or 0x8000, TTL, body.toByteArray())
    }

    private fun aRecord(): ByteArray? {
        val ip = ipAddress ?: return null
        val octets = ip.split(".").mapNotNull { it.toIntOrNull() }.filter { it in 0..255 }
        if (octets.size != 4) return null
        val host = hostname().lowercase()
        return record("$host.local.", TYPE_A, CLASS_IN or 0x8000, TTL, byteArrayOf(
            octets[0].toByte(), octets[1].toByte(), octets[2].toByte(), octets[3].toByte()
        ))
    }

    private fun txtRecord(): ByteArray? {
        val fields = listOf("path=/")
        val body = ByteArrayOutputStream()
        fields.forEach { field ->
            val b = field.toByteArray(Charsets.UTF_8)
            if (b.size > 255) return null
            body.write(b.size)
            body.write(b)
        }
        return record(serviceInstance(), TYPE_TXT, CLASS_IN, TTL, body.toByteArray())
    }

    private fun record(name: String, type: Int, cls: Int, ttl: Long, rdata: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeName(name) ?: byteArrayOf(0))
        writeU16(out, type)
        writeU16(out, cls)
        writeU32(out, ttl)
        writeU16(out, rdata.size)
        out.write(rdata)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // DNS helpers
    // ------------------------------------------------------------------

    private fun writeHeader(out: ByteArrayOutputStream, answers: Int) {
        writeU16(out, 0)                       // ID
        writeU16(out, 0x8400)                  // QR=1, AA=1
        writeU16(out, 0)                       // QDCOUNT
        writeU16(out, answers)                 // ANCOUNT
        writeU16(out, 0)                       // NSCOUNT
        writeU16(out, 0)                       // ARCOUNT
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    /** Encodes a dotted name like `a._http._tcp.local.` into DNS labels. */
    private fun encodeName(name: String): ByteArray? {
        val labels = name.trimEnd('.').split(".")
        if (labels.isEmpty()) return null
        val out = ByteArrayOutputStream()
        labels.forEach { label ->
            val b = label.toByteArray(Charsets.UTF_8)
            if (b.isEmpty() || b.size > MAX_LABEL) return null
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        return out.toByteArray()
    }

    /** Reads a (possibly compressed) name; returns the name and next offset. */
    private fun readName(data: ByteArray, start: Int): Pair<String, Int>? {
        var pos = start
        val labels = mutableListOf<String>()
        var endPos = -1
        var guard = 0
        while (pos < data.size && guard++ < 128) {
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (endPos < 0) endPos = pos + 1
                    return labels.joinToString(".") to endPos
                }
                (len and 0xC0) == 0xC0 -> {
                    if (pos + 1 >= data.size) return null
                    val ptr = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    if (endPos < 0) endPos = pos + 2
                    if (ptr >= data.size) return null
                    pos = ptr
                }
                (len and 0xC0) != 0 -> return null
                else -> {
                    if (pos + 1 + len > data.size) return null
                    labels += String(data, pos + 1, len, Charsets.UTF_8)
                    pos += 1 + len
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------

    /** The device's LAN IPv4 (first non-loopback, non-link-local address). */
    private fun findIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in java.util.Collections.list(interfaces)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in java.util.Collections.list(ni.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** DNS-safe lowercase hostname from the configured device name. */
    private fun sanitizeHost(raw: String): String {
        var host = raw.trim().lowercase()
        host = host.replace(Regex("[^a-z0-9-]"), "-")
        host = host.replace(Regex("-+"), "-").trim('-')
        if (host.isBlank()) host = "screentamer"
        return host.take(MAX_LABEL)
    }

    private fun buildResponseSummary(data: ByteArray, length: Int): String {
        try {
            if (length < 12) return ""
            val qd = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            var pos = 12
            val names = mutableListOf<String>()
            repeat(qd) {
                val (name, next) = readName(data, pos) ?: return@repeat
                names.add(name)
                pos = next + 4
            }
            return names.joinToString(", ")
        } catch (e: Exception) {
            return ""
        }
    }
}
