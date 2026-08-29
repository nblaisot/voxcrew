@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
        fun sentActivity(): List<LanFrame.MediaActivity> = sent.filterIsInstance<LanFrame.MediaActivity>()
    }

    @Test
    fun `media activity is sequenced buffered and replayed with audio`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        peerLink.sendMediaActivity(true)
        peerLink.send(byteArrayOf(3))
        peerLink.sendMediaActivity(false)

        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        assertEquals(
            listOf(
                LanFrame.MediaActivity(0, true),
                LanFrame.Audio(1, byteArrayOf(3)),
                LanFrame.MediaActivity(2, false),
            ).map { it::class },
            transport.sent.map { it::class },
        )
        assertEquals(listOf(true, false), transport.sentActivity().map { it.active })
        peerLink.clear()
    }

    @Test
    fun `out of order sequenced media waits for the missing frame`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        peerLink.onFrameReceived(transport, LanFrame.Audio(1, byteArrayOf(2)))
        assertEquals(-1L, peerLink.lastContiguousInSeq())
        peerLink.onFrameReceived(transport, LanFrame.MediaActivity(0, true))

        assertEquals(1L, peerLink.lastContiguousInSeq())
        peerLink.clear()
    }

    @Test
    fun `incoming talk boundaries and audio are emitted in sequence order`() = runTest {
        var monotonicNs = 100L
        val peerLink = PeerLink(this, clockNs = { monotonicNs })
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)
        val events = mutableListOf<IncomingMediaEvent>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            peerLink.incomingMedia.take(3).toList(events)
        }

        peerLink.onFrameReceived(transport, LanFrame.Audio(1, byteArrayOf(9)))
        monotonicNs = 200L
        peerLink.onFrameReceived(transport, LanFrame.MediaActivity(0, true))
        monotonicNs = 300L
        peerLink.onFrameReceived(transport, LanFrame.MediaActivity(2, false))
        collectJob.join()

        val started = events[0] as IncomingMediaEvent.Activity
        val audio = events[1] as IncomingMediaEvent.Audio
        val stopped = events[2] as IncomingMediaEvent.Activity
        assertEquals(0L, started.sequence)
        assertTrue(started.active)
        assertEquals(1L, audio.sequence)
        assertEquals(9, audio.payload.single().toInt())
        assertEquals(2L, stopped.sequence)
        assertFalse(stopped.active)
        assertEquals(200L, started.receivedAtNs)
        assertEquals(100L, audio.receivedAtNs)
        assertEquals(300L, stopped.receivedAtNs)
        peerLink.clear()
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
    fun `transport replace never flickers Disconnected when old stop calls onDisconnected`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transportA = object : FrameTransport {
            override val label = PathLabels.CLOUD
            override fun sendFrame(frame: LanFrame) = Unit
            override fun dropAndRetry() = Unit
            override fun stop() {
                peerLink.onDisconnected(this, "peer-b")
            }
        }
        peerLink.onHandshakeComplete(transportA, "peer-b", -1)
        assertEquals(PeerLink.LinkState.Connected("peer-b", PathLabels.CLOUD), peerLink.state.value)

        val transportB = FakeTransport(PathLabels.LOCAL)
        peerLink.onHandshakeComplete(transportB, "peer-b", -1)

        // Must stay Connected to Local — Cloud stop()'s onDisconnected must not win.
        assertEquals(PeerLink.LinkState.Connected("peer-b", PathLabels.LOCAL), peerLink.state.value)
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
    fun `evaluateLiveness ignores overdue pong when frame activity is fresh`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        val now = System.currentTimeMillis()
        peerLink.markPingSentForTest(now - 10_000L)

        assertTrue(peerLink.evaluateLiveness(now + 1_000L))
        peerLink.clear()
    }

    @Test
    fun `evaluateLiveness fails when frame activity exceeds peer timeout`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        val now = System.currentTimeMillis()
        assertFalse(peerLink.evaluateLiveness(now + 7_000L))
        peerLink.clear()
    }

    @Test
    fun `evaluateLiveness passes after recent ack activity`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        val now = System.currentTimeMillis()
        peerLink.markPingSentForTest(now)
        peerLink.onFrameReceived(transport, LanFrame.Ack(0))

        assertTrue(peerLink.evaluateLiveness(now + 4_000L))
        peerLink.clear()
    }

    @Test
    fun `expired frames while apart are declared with skip so replay resumes cleanly`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transportA = FakeTransport("A")
        peerLink.onHandshakeComplete(transportA, "peer-b", -1)

        peerLink.send(byteArrayOf(1)) // seq 0
        peerLink.send(byteArrayOf(2)) // seq 1
        peerLink.onDisconnected(transportA, "peer-b")

        // While disconnected, the unacked frames age out of the send buffer.
        peerLink.expireStaleFramesForTest(nowMs = System.currentTimeMillis() + 60_000)
        peerLink.send(byteArrayOf(3)) // seq 2, freshly enqueued

        // Reconnect: the peer announces it never got anything (-1). Seq 0..1 are gone,
        // so the sender must declare the hole before replaying seq 2.
        val transportB = FakeTransport("B")
        peerLink.onHandshakeComplete(transportB, "peer-b", -1)

        val skips = transportB.sent.filterIsInstance<LanFrame.Skip>()
        assertEquals(listOf(1L), skips.map { it.untilSeq })
        assertEquals(listOf(2L), transportB.sentAudio().map { it.seq })
        // Skip precedes the replayed audio on the wire.
        assertTrue(transportB.sent.indexOfFirst { it is LanFrame.Skip } <
            transportB.sent.indexOfFirst { it is LanFrame.Audio })
        peerLink.clear()
    }

    @Test
    fun `receiver fast-forwards over a declared hole and delivers buffered frames`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        // Frames 0..2 never arrive; 3 and 4 are parked out of order.
        peerLink.onFrameReceived(transport, LanFrame.Audio(3, byteArrayOf(13)))
        peerLink.onFrameReceived(transport, LanFrame.Audio(4, byteArrayOf(14)))
        assertEquals(-1L, peerLink.lastContiguousInSeq())

        peerLink.onFrameReceived(transport, LanFrame.Skip(2))

        // Contiguity jumped over the hole and drained the parked frames.
        assertEquals(4L, peerLink.lastContiguousInSeq())
        peerLink.clear()
    }

    @Test
    fun `stale skip never rewinds contiguity`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        peerLink.onFrameReceived(transport, LanFrame.Audio(0, byteArrayOf(1)))
        peerLink.onFrameReceived(transport, LanFrame.Audio(1, byteArrayOf(2)))
        peerLink.onFrameReceived(transport, LanFrame.Skip(0))

        assertEquals(1L, peerLink.lastContiguousInSeq())
        peerLink.clear()
    }

    @Test
    fun `live expiry declares the hole on the next send`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transportA = FakeTransport("A")
        peerLink.onHandshakeComplete(transportA, "peer-b", -1)

        peerLink.send(byteArrayOf(1)) // seq 0, never acked
        // Frame ages out while the transport stays attached (receiver stalled).
        peerLink.expireStaleFramesForTest(nowMs = System.currentTimeMillis() + 60_000)
        peerLink.send(byteArrayOf(2)) // seq 1

        val skips = transportA.sent.filterIsInstance<LanFrame.Skip>()
        assertEquals(listOf(0L), skips.map { it.untilSeq })
        peerLink.clear()
    }

    @Test
    fun `disconnected buffer ages out automatically past TTL without further sends`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val peerLink = PeerLink(
            this,
            healthDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
        )
        peerLink.resetFor("peer-b")
        peerLink.send(byteArrayOf(1))
        peerLink.send(byteArrayOf(2))
        testScheduler.runCurrent()
        assertEquals(40L, peerLink.backlogMs.value)
        assertEquals(2, peerLink.unacknowledgedFrames().size)

        // Exactly at max age frames are kept; one ms past they must drop without a new send.
        testScheduler.advanceTimeBy(SendBuffer.DEFAULT_MAX_AGE_MS)
        testScheduler.runCurrent()
        assertEquals(40L, peerLink.backlogMs.value)

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()

        assertEquals(0L, peerLink.backlogMs.value)
        assertTrue(peerLink.unacknowledgedFrames().isEmpty())
        peerLink.clear()
    }

    @Test
    fun `health loop restarts after a disconnect and reconnect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val peerLink = PeerLink(
            this,
            healthDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
        )
        peerLink.resetFor("peer-b")

        val transportA = FakeTransport("A")
        peerLink.onHandshakeComplete(transportA, "peer-b", -1)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertTrue(transportA.sent.any { it is LanFrame.Ping })
        assertFalse(transportA.sent.any { it is LanFrame.Ack })

        peerLink.onDisconnected(transportA, "peer-b")
        val transportB = FakeTransport("B")
        peerLink.onHandshakeComplete(transportB, "peer-b", -1)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()

        assertTrue(transportB.sent.any { it is LanFrame.Ping })
        peerLink.clear()
    }

    @Test
    fun `idle link sends ping heartbeat without periodic ack`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val peerLink = PeerLink(this, dispatcher) { testScheduler.currentTime }
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)

        testScheduler.advanceTimeBy(4_100)
        testScheduler.runCurrent()

        assertEquals(3, transport.sent.filterIsInstance<LanFrame.Ping>().size)
        assertTrue(transport.sent.none { it is LanFrame.Ack })
        peerLink.clear()
    }

    @Test
    fun `incoming media is acknowledged within coalescing interval`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val peerLink = PeerLink(this, dispatcher) { testScheduler.currentTime }
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", -1)
        testScheduler.runCurrent()

        peerLink.onFrameReceived(transport, LanFrame.Audio(0, byteArrayOf(1)))
        testScheduler.advanceTimeBy(249)
        testScheduler.runCurrent()
        assertTrue(transport.sent.none { it is LanFrame.Ack })
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()

        assertEquals(
            listOf(0L),
            transport.sent.filterIsInstance<LanFrame.Ack>().map { it.lastContiguousSeq },
        )
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

    @Test
    fun `peer announced sequence higher than local outSeq fast forwards outSeq for next transmission`() {
        val peerLink = newPeerLink()
        peerLink.resetFor("peer-b")
        val transport = FakeTransport("A")
        peerLink.onHandshakeComplete(transport, "peer-b", 50)

        peerLink.send(byteArrayOf(42))
        assertEquals(listOf(51L), transport.sentAudio().map { it.seq })
        peerLink.clear()
    }

    @Test
    fun `reconnected peer after sequence reset can transmit audio without remote drop`() {
        val senderLink = newPeerLink()
        senderLink.resetFor("peer-b")
        val senderTransport = FakeTransport("A")
        senderLink.onHandshakeComplete(senderTransport, "peer-b", -1)

        val receiverLink = newPeerLink()
        receiverLink.resetFor("peer-a")
        val receiverTransport = FakeTransport("B")
        receiverLink.onHandshakeComplete(receiverTransport, "peer-a", -1)

        // Sender sends 3 frames (0, 1, 2)
        senderLink.send(byteArrayOf(1))
        senderLink.send(byteArrayOf(2))
        senderLink.send(byteArrayOf(3))

        // Receiver receives them
        receiverLink.onFrameReceived(receiverTransport, LanFrame.Audio(0, byteArrayOf(1)))
        receiverLink.onFrameReceived(receiverTransport, LanFrame.Audio(1, byteArrayOf(2)))
        receiverLink.onFrameReceived(receiverTransport, LanFrame.Audio(2, byteArrayOf(3)))
        assertEquals(2L, receiverLink.lastContiguousInSeq())

        // Sender resets (e.g. connection recreated)
        senderLink.resetFor("peer-b")

        // Sender reconnects and handshake receives peer's last contiguous seq (2)
        val newSenderTransport = FakeTransport("A2")
        senderLink.onHandshakeComplete(newSenderTransport, "peer-b", 2)

        // Sender produces a new audio frame
        senderLink.send(byteArrayOf(4))
        val newAudio = newSenderTransport.sentAudio().last()
        assertEquals(3L, newAudio.seq)

        // Receiver receives the new frame without dropping it
        receiverLink.onFrameReceived(receiverTransport, newAudio)
        assertEquals(3L, receiverLink.lastContiguousInSeq())

        senderLink.clear()
        receiverLink.clear()
    }
}
