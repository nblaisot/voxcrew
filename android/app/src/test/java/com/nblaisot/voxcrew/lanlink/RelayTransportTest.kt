package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake cloud relay: forwards whatever one side sends, byte for byte, to the other's
 * [incomingBinary] — exactly what the backend does (`onBinaryMessage` forwards the
 * opaque payload uid-to-uid without parsing it), so [RelayTransport] must do its own
 * header framing/stripping on top.
 */
private class FakeRelayChannel : BinaryRelayChannel {
    lateinit var peer: FakeRelayChannel
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingBinary: SharedFlow<ByteArray> = incoming.asSharedFlow()
    var connectCalls = 0

    override fun connect() {
        connectCalls++
    }

    override fun sendBinary(bytes: ByteArray) {
        peer.incoming.tryEmit(bytes)
    }
}

class RelayTransportTest {

    private fun realScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun connectedPair(): Pair<FakeRelayChannel, FakeRelayChannel> {
        val channelA = FakeRelayChannel()
        val channelB = FakeRelayChannel()
        channelA.peer = channelB
        channelB.peer = channelA
        return channelA to channelB
    }

    @Test
    fun `two transports complete resume handshake and exchange audio both ways`() = runBlocking {
        val scopeA = realScope()
        val scopeB = realScope()
        val peerLinkA = PeerLink(scopeA)
        val peerLinkB = PeerLink(scopeB)
        peerLinkA.resetFor("peer-b")
        peerLinkB.resetFor("peer-a")
        val (channelA, channelB) = connectedPair()

        val transportA = RelayTransport(scopeA, peerLinkA, channelA)
        val transportB = RelayTransport(scopeB, peerLinkB, channelB)

        transportA.start("peer-a", "peer-b")
        transportB.start("peer-b", "peer-a")

        withTimeout(3_000) {
            while (
                peerLinkA.state.value !is PeerLink.LinkState.Connected ||
                peerLinkB.state.value !is PeerLink.LinkState.Connected
            ) {
                delay(20)
            }
        }
        assertTrue(channelA.connectCalls >= 1)
        assertTrue(channelB.connectCalls >= 1)

        val fromAtoBDeferred = async { withTimeout(3_000) { peerLinkB.incomingAudio.first() } }
        delay(50)
        peerLinkA.send(byteArrayOf(42))
        assertEquals(42.toByte(), fromAtoBDeferred.await()[0])

        val fromBtoADeferred = async { withTimeout(3_000) { peerLinkA.incomingAudio.first() } }
        delay(50)
        peerLinkB.send(byteArrayOf(7))
        assertEquals(7.toByte(), fromBtoADeferred.await()[0])

        transportA.stop()
        transportB.stop()
        peerLinkA.clear()
        peerLinkB.clear()
        scopeA.cancel()
        scopeB.cancel()
    }

    @Test
    fun `sendFrame prefixes the peer uid header the backend needs to route the frame`() = runBlocking {
        val scopeA = realScope()
        val peerLinkA = PeerLink(scopeA)
        peerLinkA.resetFor("peer-b")
        val channel = FakeRelayChannel()
        val loopback = FakeRelayChannel()
        channel.peer = loopback
        loopback.peer = channel
        val transportA = RelayTransport(scopeA, peerLinkA, channel)

        var captured: ByteArray? = null
        val capturingChannel = object : BinaryRelayChannel by channel {
            override fun sendBinary(bytes: ByteArray) {
                captured = bytes
            }
        }
        val transport = RelayTransport(scopeA, peerLinkA, capturingChannel)
        transport.start("peer-a", "peer-b")

        withTimeout(1_000) {
            while (captured == null) delay(10)
        }
        val bytes = requireNotNull(captured)
        val recipientLen = bytes[0].toInt() and 0xFF
        val recipient = String(bytes, 1, recipientLen, Charsets.UTF_8)
        assertEquals("peer-b", recipient)
        val frame = LanProtocol.decodeFrame(bytes.copyOfRange(1 + recipientLen, bytes.size))
        assertTrue(frame is LanFrame.Hello)

        transport.stop()
        transportA.stop()
        peerLinkA.clear()
        scopeA.cancel()
    }

    @Test
    fun `dropAndRetry reconnects the cloud channel and resends the handshake`() = runBlocking {
        val scopeA = realScope()
        val peerLinkA = PeerLink(scopeA)
        peerLinkA.resetFor("peer-b")
        val channel = FakeRelayChannel()
        val discard = FakeRelayChannel()
        channel.peer = discard
        discard.peer = channel
        val transport = RelayTransport(scopeA, peerLinkA, channel)
        transport.start("peer-a", "peer-b")
        val callsAfterStart = channel.connectCalls
        assertTrue(callsAfterStart >= 1)

        transport.dropAndRetry()
        assertTrue(channel.connectCalls > callsAfterStart)

        transport.stop()
        peerLinkA.clear()
        scopeA.cancel()
    }
}
