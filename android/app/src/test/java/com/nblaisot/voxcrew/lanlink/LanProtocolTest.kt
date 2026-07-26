package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class LanProtocolTest {

    private fun roundTrip(frame: LanFrame): LanFrame? {
        val buffer = ByteArrayOutputStream()
        LanProtocol.writeFrame(DataOutputStream(buffer), frame)
        val input = DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
        return LanProtocol.readFrame(input)
    }

    @Test
    fun `hello frame round trips`() {
        val frame = LanFrame.Hello(uid = "user-a", lastContiguousSeq = 42L)
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun `hello frame with no prior seq uses -1 sentinel`() {
        val frame = LanFrame.Hello(uid = "user-b", lastContiguousSeq = -1L)
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun `hello with relay offer round trips`() {
        val offer = com.nblaisot.voxcrew.relay.RelayConfigLink(
            url = "wss://mini.example:8443",
            secret = "crew-secret",
            certSha256 = "deadbeef",
        )
        val frame = LanFrame.Hello("user-a", 3L, offer)
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun `hello without offer still decodes when trailing bytes absent`() {
        val classic = LanFrame.Hello("user-a", -1L, relayOffer = null)
        val decoded = roundTrip(classic) as LanFrame.Hello
        assertEquals("user-a", decoded.uid)
        assertEquals(-1L, decoded.lastContiguousSeq)
        assertNull(decoded.relayOffer)
    }

    @Test
    fun `encodeFrame hello offer round trips for datagram path`() {
        val offer = com.nblaisot.voxcrew.relay.RelayConfigLink(
            url = "wss://x",
            secret = "s",
            certSha256 = null,
        )
        val bytes = LanProtocol.encodeFrame(LanFrame.Hello("u", 0L, offer))
        val decoded = LanProtocol.decodeFrame(bytes) as LanFrame.Hello
        assertEquals(offer, decoded.relayOffer)
    }

    @Test
    fun `audio frame round trips with pcm payload preserved exactly`() {
        val pcm = ByteArray(640) { (it % 256).toByte() }
        val frame = LanFrame.Audio(seq = 7L, payload = pcm)
        val decoded = roundTrip(frame) as LanFrame.Audio
        assertEquals(7L, decoded.seq)
        assertArrayEquals(pcm, decoded.payload)
    }

    @Test
    fun `ack frame round trips`() {
        val frame = LanFrame.Ack(lastContiguousSeq = 123L)
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun `ping and pong frames round trip`() {
        val ping = LanFrame.Ping(timestampMs = 1_000L)
        val pong = LanFrame.Pong(timestampMs = 2_000L)
        assertEquals(ping, roundTrip(ping))
        assertEquals(pong, roundTrip(pong))
    }

    @Test
    fun `media activity frames round trip`() {
        assertEquals(LanFrame.MediaActivity(7L, true), roundTrip(LanFrame.MediaActivity(7L, true)))
        assertEquals(LanFrame.MediaActivity(8L, false), roundTrip(LanFrame.MediaActivity(8L, false)))
    }

    @Test
    fun `multiple frames can be written and read sequentially from the same stream`() {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        val frames = listOf(
            LanFrame.Hello("user-a", -1L),
            LanFrame.Audio(0L, byteArrayOf(1, 2, 3)),
            LanFrame.Audio(1L, byteArrayOf(4, 5, 6)),
            LanFrame.Ack(1L),
        )
        frames.forEach { LanProtocol.writeFrame(out, it) }

        val input = DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
        val decoded = frames.map { LanProtocol.readFrame(input) }

        assertEquals(frames[0], decoded[0])
        val audio0 = decoded[1] as LanFrame.Audio
        assertEquals(0L, audio0.seq)
        assertArrayEquals(byteArrayOf(1, 2, 3), audio0.payload)
        val audio1 = decoded[2] as LanFrame.Audio
        assertEquals(1L, audio1.seq)
        assertArrayEquals(byteArrayOf(4, 5, 6), audio1.payload)
        assertEquals(frames[3], decoded[3])
    }

    @Test
    fun `encodeFrame and decodeFrame round trip for datagram transports`() {
        val frame = LanFrame.Audio(seq = 3L, payload = byteArrayOf(9, 8, 7))
        val bytes = LanProtocol.encodeFrame(frame)
        val decoded = LanProtocol.decodeFrame(bytes) as LanFrame.Audio
        assertEquals(3L, decoded.seq)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded.payload)
    }

    @Test
    fun `decodeFrame returns null for empty bytes`() {
        assertNull(LanProtocol.decodeFrame(ByteArray(0)))
    }

    @Test
    fun `reading past end of stream returns null`() {
        val input = DataInputStream(ByteArrayInputStream(ByteArray(0)))
        assertNull(LanProtocol.readFrame(input))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it]) }
    }
}
