package com.screentamer.agent.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Unit tests for [MdnsAdvertiser.matchCollisionRecord]: the pure packet parser
 * that decides whether an incoming mDNS response/announcement from another
 * device is broadcasting a name that collides with ours.
 */
class MdnsCollisionTest {

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    /** Wire-encodes a DNS name from its dotted form, e.g. "screentamer.local." */
    private fun wireName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.trimEnd('.').split(".").forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        return out.toByteArray()
    }

    // "screentamer.local." as uncompressed labels.
    private val hostName = wireName("screentamer.local.")

    // "screentamer._http._tcp.local." as uncompressed labels.
    private val instanceName = wireName("screentamer._http._tcp.local.")

    /** Header with QR=1 (response), then the given records. */
    private fun response(vararg records: ByteArray): ByteArray {
        val an = records.size
        val header = bytes(0, 0, 0x84, 0x00, 0, 0, (an ushr 8) and 0xFF, an and 0xFF, 0, 0, 0, 0)
        return header + records.fold(ByteArray(0)) { acc, r -> acc + r }
    }

    /** One RR: name + type + class + ttl + rdata. */
    private fun record(name: ByteArray, type: Int, rdata: ByteArray): ByteArray =
        name +
            bytes((type ushr 8) and 0xFF, type and 0xFF, 0, 1, 0, 0, 0, 120) +
            bytes((rdata.size ushr 8) and 0xFF, rdata.size and 0xFF) +
            rdata

    private val foreignIp = bytes(192, 0, 2, 99)

    private val instanceNameStr = "screentamer._http._tcp.local."

    @Test
    fun `A record for our hostname from another device is a collision`() {
        val pkt = response(record(hostName, 1, foreignIp))
        assertEquals("screentamer.local.", MdnsAdvertiser.matchCollisionRecord(pkt, pkt.size, "screentamer.local.", instanceNameStr))
    }

    @Test
    fun `PTR for our service instance is a collision`() {
        val pkt = response(record(instanceName, 12, instanceName))
        assertEquals(
            instanceNameStr,
            MdnsAdvertiser.matchCollisionRecord(pkt, pkt.size, "screentamer.local.", instanceNameStr)
        )
    }

    @Test
    fun `SRV for our service instance is a collision`() {
        val pkt = response(record(instanceName, 33, foreignIp))
        assertEquals(
            instanceNameStr,
            MdnsAdvertiser.matchCollisionRecord(pkt, pkt.size, "screentamer.local.", instanceNameStr)
        )
    }

    @Test
    fun `our own response is not a collision`() {
        // A QR=1 packet that only carries a *different* hostname.
        val otherHost = wireName("myfire.local.")
        val pkt = response(record(otherHost, 1, foreignIp))
        assertNull(MdnsAdvertiser.matchCollisionRecord(pkt, pkt.size, "screentamer.local.", instanceNameStr))
    }

    @Test
    fun `queries QR=0 are ignored`() {
        // Header with QR=0 (a question from someone else), plus a matching A in answers.
        val header = bytes(0, 0, 0x00, 0x00, 1, 0, 1, 0, 0, 0, 0, 0)
        val question = hostName + bytes(0, 1, 0, 1)
        val pkt = header + question + record(hostName, 1, foreignIp)
        assertNull(MdnsAdvertiser.matchCollisionRecord(pkt, pkt.size, "screentamer.local.", instanceNameStr))
    }

    @Test
    fun `truncated packet does not throw`() {
        val pkt = response(record(hostName, 1, foreignIp))
        val truncated = pkt.copyOf(20)
        assertNull(MdnsAdvertiser.matchCollisionRecord(truncated, truncated.size, "screentamer.local.", instanceNameStr))
    }
}
