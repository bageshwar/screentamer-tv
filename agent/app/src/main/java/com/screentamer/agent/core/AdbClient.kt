package com.screentamer.agent.core

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Minimal ADB client speaking to the device's own adbd over TCP loopback
 * (127.0.0.1:5555). Used to inject media keys, go home and force-stop apps,
 * exactly as a desktop `adb` host would.
 *
 * Wire format: every message is a 24-byte header followed by a payload.
 *   bytes 0-3    : 4-char command ("AUTH", "CNXN", "OPEN", ...)
 *   bytes 4-7    : arg0 as a little-endian u32
 *   bytes 8-11   : arg1 as a little-endian u32
 *   bytes 12-15  : payload length as a little-endian u16 (last 2 bytes unused)
 * All integer fields are BINARY little-endian (this is the real adb framing,
 * verified against a live adbd capture), not hex-encoded text.
 */
class AdbClient(private val context: Context) {

    companion object {
        private const val TAG = "ScreenTamer/AdbClient"
        private const val DEFAULT_TIMEOUT_MS = 8000

        // ADB auth message arg0 values
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        // CNXN hello values used by the real adb client
        private const val CNXN_VERSION = 0x01000001L
        private const val CNXN_MAXDATA = 0x00100000L
    }

    private data class Message(val command: String, val arg0: Long, val arg1: Long, val payload: ByteArray)

    @Volatile
    private var socket: Socket? = null

    private val keyPair: KeyPair by lazy { loadOrCreateKey() }

    /** Executes a shell command (e.g. "input keyevent 127"). Returns true on success. */
    fun runShell(command: String): Boolean {
        return try {
            synchronized(this) {
                val s = connect()
                when (AdbConfigProvider.get(context).mode) {
                    AdbMode.DEVICE -> {
                        sendOpen(s, "shell:$command")
                        // Give adbd a moment to run the command, then close the transport.
                        // A fresh authenticated connection per command keeps stream ids clean.
                        drainUntilClose(s, 4000)
                    }
                    AdbMode.HOST_BRIDGE -> bridgeShell(s, command)
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "adb shell failed: $command -> ${e.message}")
            Log.d(TAG, "adb shell failure detail", e)
            false
        } finally {
            closeSocket()
        }
    }

    fun inputKeyEvent(keycode: Int): Boolean = runShell("input keyevent $keycode")

    fun forceStop(pkg: String): Boolean = runShell("am force-stop $pkg")

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private fun connect(): Socket {
        socket?.let { if (!it.isClosed && it.isConnected) return it }
        val cfg = AdbConfigProvider.get(context)
        Log.i(TAG, "adb connecting (${cfg.mode}, ${cfg.host}:${cfg.port}${if (cfg.transportId.isNotBlank()) " transport=${cfg.transportId}" else ""})")
        val s = Socket()
        s.connect(InetSocketAddress(cfg.host, cfg.port), DEFAULT_TIMEOUT_MS)
        s.soTimeout = DEFAULT_TIMEOUT_MS
        when (cfg.mode) {
            AdbMode.DEVICE -> handshake(s)
            AdbMode.HOST_BRIDGE -> bridgeHandshake(s, cfg)
        }
        socket = s
        return s
    }

    /**
     * Host-bridge mode: instead of talking to the device's own adbd we talk to
     * a desktop adb server (host:port) and select a connected device transport.
     * This is how the test rig reaches the emulator through the qemu pipe, and
     * how a parent's computer could bridge an agent to a TV.
     *
     * The adb server accepts LEGACY framing for "host:" services on its raw
     * socket: a 4-hex-char length prefix, then the service string, then a raw
     * "OKAY"/"FAIL" reply (FAIL carries a length-prefixed error). Once the
     * transport is selected the same socket becomes a device stream and the
     * framed protocol (OPEN/OKAY/WRTE/CLSE) applies again.
     */
    private fun bridgeHandshake(s: Socket, cfg: AdbConfig) {
        val transportId = cfg.transportId
        if (transportId.isBlank()) throw IllegalStateException("bridge: no transport id configured")
        val out = s.getOutputStream()
        val request = "host:transport:$transportId"
        val legacyHeader = String.format("%04x", request.length)
        out.write((legacyHeader + request).toByteArray(Charsets.UTF_8))
        out.flush()
        Log.i(TAG, "bridge: sent $request")

        val deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS
        val status = ByteArray(4)
        readFully(s, status, deadline)
        val statusStr = String(status, Charsets.US_ASCII)
        if (statusStr == "FAIL") {
            val lenBytes = ByteArray(4)
            readFully(s, lenBytes, deadline)
            val len = String(lenBytes, Charsets.US_ASCII).toInt(16)
            val err = ByteArray(len)
            readFully(s, err, deadline)
            throw IllegalStateException("bridge: transport '$transportId' not found (${String(err, Charsets.UTF_8)})")
        }
        if (statusStr != "OKAY") throw IllegalStateException("bridge: unexpected reply $statusStr")
        Log.i(TAG, "bridge: transport $transportId selected")
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun handshake(s: Socket) {
        val out = s.getOutputStream()

        // 1) Announce ourselves with a CNXN hello (modern adbd expects the
        //    client to open the handshake; it then challenges us if the key
        //    is not yet trusted).
        val hello = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb"
        writeMessage(out, "CNXN", CNXN_VERSION, CNXN_MAXDATA, hello.toByteArray(Charsets.UTF_8))
        Log.i(TAG, "hs: sent CNXN")

        // 2) adbd either accepts (CNXN) or challenges us (AUTH TOKEN).
        var (cmd, arg0, _, payload) = readMessage(s)
        Log.i(TAG, "hs: first reply $cmd/$arg0")
        if (cmd == "CNXN") return // already trusted

        if (cmd != "AUTH" || arg0 != AUTH_TOKEN.toLong()) {
            throw IllegalStateException("unexpected handshake reply: $cmd/$arg0")
        }

        // 3) Sign the challenge and present the signature.
        val signature = signToken(payload)
        writeMessage(out, "AUTH", AUTH_SIGNATURE.toLong(), 0, signature)
        Log.i(TAG, "hs: sent signature (${signature.size} bytes)")

        val reply = readMessage(s)
        Log.i(TAG, "hs: second reply ${reply.command}/${reply.arg0}")
        if (reply.command == "CNXN") return

        if (reply.command == "AUTH" && reply.arg0 == AUTH_TOKEN.toLong()) {
            // Key not trusted yet. Register it by sending the public key blob.
            // Fire TV adbd stores unknown keys without a confirmation dialog.
            val pubLine = "$pubKeyBlobB64 screentamer-agent@localhost"
            writeMessage(out, "AUTH", AUTH_RSAPUBLICKEY.toLong(), 0, pubLine.toByteArray(Charsets.US_ASCII))
            Log.i(TAG, "hs: sent public key (${pubLine.length} bytes)")
            val finalMsg = readMessage(s)
            Log.i(TAG, "hs: third reply ${finalMsg.command}/${finalMsg.arg0}")
            if (finalMsg.command != "CNXN") {
                throw IllegalStateException("key registration rejected: ${finalMsg.command}")
            }
            return
        }
        throw IllegalStateException("auth failed: ${reply.command}")
    }

    private fun sendOpen(s: Socket, service: String) {
        val out = s.getOutputStream()
        writeMessage(out, "OPEN", 1, 0, (service + "\u0000").toByteArray(Charsets.UTF_8))
    }

    /**
     * Bridge-mode shell: after transport selection the adb server keeps the
     * socket in legacy framing — the service request is length-prefixed text
     * and the output is a raw byte stream that ends when the shell exits.
     */
    private fun bridgeShell(s: Socket, command: String) {
        val service = "shell:$command"
        val out = s.getOutputStream()
        out.write((String.format("%04x", service.length) + service).toByteArray(Charsets.UTF_8))
        out.flush()
        val deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS
        val status = ByteArray(4)
        readFully(s, status, deadline)
        val statusStr = String(status, Charsets.US_ASCII)
        if (statusStr == "FAIL") {
            val lenBytes = ByteArray(4)
            readFully(s, lenBytes, deadline)
            val len = String(lenBytes, Charsets.US_ASCII).toInt(16)
            val err = ByteArray(len)
            readFully(s, err, deadline)
            throw IllegalStateException("bridge shell failed: ${String(err, Charsets.UTF_8)}")
        }
        if (statusStr != "OKAY") throw IllegalStateException("bridge shell: unexpected reply $statusStr")
        drainRaw(s, 4000)
    }

    /** Reads raw bytes until EOF or the timeout (legacy streams are unframed). */
    private fun drainRaw(s: Socket, timeoutMs: Long) {
        val ins = s.getInputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            s.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val n = try {
                ins.read()
            } catch (e: java.net.SocketTimeoutException) {
                break
            }
            if (n < 0) break
        }
    }

    /**
     * After OPEN, adbd echoes OPEN and then streams output; it sends CLSE once
     * the shell process exits. We simply read until CLSE or the timeout.
     */
    private fun drainUntilClose(s: Socket, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val (cmd, _, _, _) = tryReadMessage(s, deadline) ?: return
            if (cmd == "CLSE") return
            if (cmd == "FAIL") return
        }
    }

    // ------------------------------------------------------------------
    // Crypto
    // ------------------------------------------------------------------

    private fun signToken(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(keyPair.private)
        sig.update(token)
        return sig.sign()
    }

    private val pubKeyBlobB64: String by lazy {
        Base64.encodeToString(toAdbKeyBlob(keyPair.public as RSAPublicKey), Base64.NO_WRAP)
    }

    private fun loadOrCreateKey(): KeyPair {
        val privFile = File(context.filesDir, "adb_key_private.pem")
        val pubFile = File(context.filesDir, "adb_key_public.pem")
        if (privFile.exists() && pubFile.exists()) {
            return try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privFile.readText(), Base64.DEFAULT)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubFile.readText(), Base64.DEFAULT)))
                KeyPair(pub, priv)
            } catch (e: Exception) {
                Log.w(TAG, "failed to load adb key, regenerating", e)
                createAndStore(privFile, pubFile)
            }
        }
        return createAndStore(privFile, pubFile)
    }

    private fun createAndStore(privFile: File, pubFile: File): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        privFile.writeText(Base64.encodeToString(kp.private.encoded, Base64.DEFAULT))
        pubFile.writeText(Base64.encodeToString(kp.public.encoded, Base64.DEFAULT))
        return kp
    }

    // ------------------------------------------------------------------
    // Wire protocol
    // ------------------------------------------------------------------

    private fun writeMessage(out: OutputStream, cmd: String, arg0: Long, arg1: Long, payload: ByteArray) {
        if (payload.size > 0xFFFF) throw IllegalArgumentException("payload too large")
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.put(cmd.toByteArray(Charsets.US_ASCII))
        header.putInt(arg0.toInt())
        header.putInt(arg1.toInt())
        header.putShort(payload.size.toShort())
        out.write(header.array())
        out.write(payload)
        out.flush()
    }

    private fun readMessage(s: Socket): Message =
        tryReadMessage(s, System.currentTimeMillis() + DEFAULT_TIMEOUT_MS)
            ?: throw IllegalStateException("read timeout")

    private fun tryReadMessage(s: Socket, deadlineMs: Long): Message? {
        val header = ByteArray(24)
        readFully(s, header, deadlineMs) ?: return null
        val cmd = String(header, 0, 4, Charsets.US_ASCII)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val arg0 = bb.getInt(4).toLong() and 0xFFFFFFFFL
        val arg1 = bb.getInt(8).toLong() and 0xFFFFFFFFL
        val len = bb.getShort(12).toInt() and 0xFFFF
        val payload = ByteArray(len)
        readFully(s, payload, deadlineMs) ?: return null
        return Message(cmd, arg0, arg1, payload)
    }

    private fun readFully(s: Socket, buf: ByteArray, deadlineMs: Long): Boolean {
        val ins = s.getInputStream()
        var offset = 0
        while (offset < buf.size) {
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) return false
            s.soTimeout = remaining.toInt().coerceAtLeast(1)
            val n = ins.read(buf, offset, buf.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }
}

/**
 * Converts an RSA public key into the adbd "RSAPublicKey" blob used in the
 * AUTH RSAPUBLICKEY message (524 bytes for a 2048-bit key): little-endian
 * words plus the Montgomery values adbd needs (n0inv, R^2 mod n).
 */
fun toAdbKeyBlob(pub: RSAPublicKey): ByteArray {
    val TWO32 = BigInteger.ONE.shiftLeft(32)
    val n = pub.modulus
    val words = (n.bitLength() + 31) / 32

    fun wordsOf(value: BigInteger): ByteArray {
        val arr = ByteArray(words * 4)
        var v = value
        for (i in 0 until words) {
            val w = v.mod(TWO32).toInt()
            arr[i * 4] = (w and 0xFF).toByte()
            arr[i * 4 + 1] = ((w ushr 8) and 0xFF).toByte()
            arr[i * 4 + 2] = ((w ushr 16) and 0xFF).toByte()
            arr[i * 4 + 3] = ((w ushr 24) and 0xFF).toByte()
            v = v.shiftRight(32)
        }
        return arr
    }

    val nWords = wordsOf(n)
    val n0inv = n.mod(TWO32).modInverse(TWO32).negate().mod(TWO32).toInt()
    // R = 2^(words*32); rr = R^2 mod n
    val rr = wordsOf(BigInteger.ONE.shiftLeft(words * 64).mod(n))

    val out = ByteArray(4 + 4 + words * 4 + words * 4 + 4)
    var p = 0
    fun putInt(value: Int) {
        out[p] = (value and 0xFF).toByte()
        out[p + 1] = ((value ushr 8) and 0xFF).toByte()
        out[p + 2] = ((value ushr 16) and 0xFF).toByte()
        out[p + 3] = ((value ushr 24) and 0xFF).toByte()
        p += 4
    }
    putInt(words)
    putInt(n0inv)
    System.arraycopy(nWords, 0, out, p, nWords.size)
    p += nWords.size
    System.arraycopy(rr, 0, out, p, rr.size)
    p += rr.size
    putInt(pub.publicExponent.toInt())
    return out
}

/**
 * How the agent reaches a device: DEVICE talks to the device's own adbd over
 * loopback (real Fire TV deployment); HOST_BRIDGE talks to a desktop adb
 * server and selects a transport (test rig / parent-machine bridging).
 */
enum class AdbMode { DEVICE, HOST_BRIDGE }

data class AdbConfig(val mode: AdbMode, val host: String, val port: Int, val transportId: String)

/** Small indirection so AdbClient does not depend on app prefs directly. */
object AdbConfigProvider {
    @Volatile
    var get: (Context) -> AdbConfig = { AdbConfig(AdbMode.DEVICE, "127.0.0.1", 5555, "") }
}
