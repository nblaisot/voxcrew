package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLinkTest {

    private class FakeTransport(override val label: String) : FrameTransport {
        val sent = mutableListOf<LanFrame>()
        var stopped = false
        var droppedAndRetried = false

        override fun sendFrame(frame: LanFrame) {
            sent.add(frame)
        }

        override fun dropAndRetry() {
            droppedAndRetried = true
        }

        override fun stop() {
            stopped = true
        }

        fun sentAudio(): List<LanFrame.Audio> = sent.filterIsInstance<LanFrame.Audio>()
    }

    private fun newPeerLink(): PeerLink {
        val scope = TestScope(StandardTestDispatcher())
        return PeerLink(scope)
    }

    @Test
    fun `frames sent while disconnected are buffered then replayed exactly once a new transport adopts`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")

        val transportA = FakeTransport("A")
        peerLink.onHandshakeComplete(transportA, "peer-b", -1)

        peerLink.send(byteArrayOf(1))
        peerLink.send(byteArrayOf(2))
        assertEquals(listOf(0L, 1L), transportA.sentAudio().map { it.seq })

        // Peer confirms it received seq 0 before the link drops.
        peerLink.onFrameReceived(transportA, LanFrame.Ack(0))
        peerLink.onDisconnected(transportA, "peer-b")
        assertEquals(20L, peerLink.backlogMs.value) // one 20ms frame (seq 1) still unacked

        // Sender keeps producing while there is no transport at all — nothing is lost.
        peerLink.send(byteArrayOf(3))
        assertEquals(40L, peerLink.backlogMs.value)

        // A different transport (e.g. cloud fallback) takes over and announces it already
        // has everything up to seq 0 — exactly the gap (seq 1, 2) must be replayed, in order,
        // with no duplicate of seq 0.
        val transportB = FakeTransport("B")
        peerLink.onHandshakeComplete(transportB, "peer-b", 0)

        val replayed = transportB.sentAudio()
        assertEquals(listOf(1L, 2L), replayed.map { it.seq })
        assertEquals(byteArrayOf(2)[0], replayed[0].payload[0])
        assertEquals(byteArrayOf(3)[0], replayed[1].payload[0])

        peerLink.clear()
    }

    @Test
    fun `inbound audio is delivered in order and duplicates are dropped`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        val received = mutableListOf<Int>()
        peerLink.onFrameReceived(transport, LanFrame.Audio(0, byteArrayOf(10)))
        peerLink.onFrameReceived(transport, LanFrame.Audio(1, byteArrayOf(11)))
        peerLink.onFrameReceived(transport, LanFrame.Audio(1, byteArrayOf(99))) // stale duplicate
        peerLink.onFrameReceived(transport, LanFrame.Audio(0, byteArrayOf(99))) // stale duplicate

        assertEquals(1L, peerLink.lastContiguousInSeq())
        peerLink.clear()
    }

    @Test
    fun `pong updates rtt and stale transport is ignored after being replaced`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transportA = FakeTransport("A")
        peerLink.onHandshakeComplete(transportA, "peer-b", -1)
        val pingTs = System.currentTimeMillis() - 42
        peerLink.markPingSentForTest(pingTs)
        peerLink.onFrameReceived(transportA, LanFrame.Pong(pingTs))
        assertFalse(peerLink.rttMs.value == null)

        val transportB = FakeTransport("B")
        peerLink.onHandshakeComplete(transportB, "peer-b", -1)

        // A stale callback from the now-replaced transport A must not affect state.
        peerLink.onDisconnected(transportA, "peer-b")
        assertEquals(PeerLink.LinkState.Connected("peer-b", "B"), peerLink.state.value)

        peerLink.onDisconnected(transportB, "peer-b")
        assertEquals(PeerLink.LinkState.Disconnected("peer-b"), peerLink.state.value)
        assertNull(peerLink.rttMs.value)
        peerLink.clear()
    }

    @Test
    fun `markUnreachable transitions connected link to disconnected`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        peerLink.markUnreachable()

        assertEquals(PeerLink.LinkState.Disconnected("peer-b"), peerLink.state.value)
        assertNull(peerLink.rttMs.value)
        assertTrue(transport.droppedAndRetried)
        peerLink.clear()
    }

    @Test
    fun `evaluateLiveness fails when pong is overdue`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        val now = System.currentTimeMillis()
        peerLink.markPingSentForTest(now)

        assertTrue(peerLink.evaluateLiveness(now + 2_000))
        assertFalse(peerLink.evaluateLiveness(now + 4_000))
        peerLink.clear()
    }

    @Test
    fun `evaluateLiveness passes after pong is received`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        val now = System.currentTimeMillis()
        peerLink.markPingSentForTest(now)
        peerLink.onFrameReceived(transport, LanFrame.Pong(now))

        assertTrue(peerLink.evaluateLiveness(now + 4_000))
        peerLink.clear()
    }

    @Test
    fun `stale pong does not update rtt`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        val now = System.currentTimeMillis()
        peerLink.markPingSentForTest(now)
        peerLink.onFrameReceived(transport, LanFrame.Pong(now - 5_000))

        assertNull(peerLink.rttMs.value)
        peerLink.clear()
    }
}
