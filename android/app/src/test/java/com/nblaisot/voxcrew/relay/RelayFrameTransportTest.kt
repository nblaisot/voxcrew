package com.nblaisot.voxcrew.relay

import com.nblaisot.voxcrew.lanlink.LanFrame
import com.nblaisot.voxcrew.lanlink.PeerLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayFrameTransportTest {

    private class FakeBinarySender : RelayBinarySender {
        val sentFrames = mutableListOf<Pair<String, LanFrame>>()

        override fun sendBinary(peerUid: String, frame: LanFrame) {
            sentFrames.add(peerUid to frame)
        }
    }

    @Test
    fun `startHandshake sends hello to peer`() = runTest {
        val client = FakeBinarySender()
        val transport = RelayFrameTransport("peer-b", "local-uid", client, StandardTestDispatcher(testScheduler))
        val link = PeerLink(this)
        link.resetFor("peer-b")

        transport.startHandshake(link)
        testScheduler.runCurrent()

        assertEquals(1, client.sentFrames.size)
        val (peerUid, frame) = client.sentFrames[0]
        assertEquals("peer-b", peerUid)
        assertTrue(frame is LanFrame.Hello)
        assertEquals("local-uid", (frame as LanFrame.Hello).uid)

        link.clear()
        transport.stop()
    }

    @Test
    fun `incoming hello triggers handshake completion and sends hello if not yet sent`() = runTest {
        val client = FakeBinarySender()
        val transport = RelayFrameTransport("peer-b", "local-uid", client, StandardTestDispatcher(testScheduler))
        val link = PeerLink(this)
        link.resetFor("peer-b")

        transport.attach(link)
        // Incoming Hello arrives before local startHandshake
        transport.onRemoteFrame(LanFrame.Hello("peer-b", 10))
        testScheduler.runCurrent()

        // Should have replied with Hello
        assertEquals(1, client.sentFrames.size)
        val (peerUid, frame) = client.sentFrames[0]
        assertEquals("peer-b", peerUid)
        assertTrue(frame is LanFrame.Hello)

        // PeerLink should be Connected
        assertTrue(link.state.value is PeerLink.LinkState.Connected)

        link.clear()
        transport.stop()
    }

    @Test
    fun `incoming hello on already connected transport updates peerlink without duplicate handshake deadlock`() = runTest {
        val client = FakeBinarySender()
        val transport = RelayFrameTransport("peer-b", "local-uid", client, StandardTestDispatcher(testScheduler))
        val link = PeerLink(this)
        link.resetFor("peer-b")

        // First handshake
        transport.startHandshake(link)
        testScheduler.runCurrent()
        transport.onRemoteFrame(LanFrame.Hello("peer-b", 5))
        testScheduler.runCurrent()
        assertTrue(link.state.value is PeerLink.LinkState.Connected)

        // Remote peer reconnects and sends another Hello
        transport.onRemoteFrame(LanFrame.Hello("peer-b", 12))
        testScheduler.runCurrent()

        assertTrue(link.state.value is PeerLink.LinkState.Connected)

        link.clear()
        transport.stop()
    }
}
