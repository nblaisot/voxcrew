package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class UdpP2pTransportTest {

    private fun realScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `two transports punch loopback candidates, complete resume handshake and exchange audio both ways`() = runBlocking {
        val scopeA = realScope()
        val scopeB = realScope()
        val peerLinkA = PeerLink(scopeA)
        val peerLinkB = PeerLink(scopeB)
        peerLinkA.resetFor("peer-b")
        peerLinkB.resetFor("peer-a")

        val transportA = UdpP2pTransport(scopeA, peerLinkA, punchIntervalMs = 50, punchDurationMs = 3_000)
        val transportB = UdpP2pTransport(scopeB, peerLinkB, punchIntervalMs = 50, punchDurationMs = 3_000)
        transportA.openSocket()
        transportB.openSocket()

        val addressA = InetSocketAddress("127.0.0.1", transportA.localSocketPort)
        val addressB = InetSocketAddress("127.0.0.1", transportB.localSocketPort)

        transportA.start("peer-a", "peer-b", listOf(addressB))
        transportB.start("peer-b", "peer-a", listOf(addressA))

        withTimeout(5_000) {
            while (
                peerLinkA.state.value !is PeerLink.LinkState.Connected ||
                peerLinkB.state.value !is PeerLink.LinkState.Connected
            ) {
                delay(20)
            }
        }

        // Subscribe before sending: SharedFlow has no replay, so a collector that starts
        // after tryEmit already ran would wait forever for a value that already passed.
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
    fun `unacknowledged frames are retransmitted once the RTO elapses`() = runBlocking {
        val scopeA = realScope()
        val peerLinkA = PeerLink(scopeA)
        peerLinkA.resetFor("peer-b")
        val transportA = UdpP2pTransport(
            scope = scopeA,
            peerLink = peerLinkA,
            punchIntervalMs = 50,
            punchDurationMs = 3_000,
            rtoMs = 150,
            rtoCheckIntervalMs = 40,
        )
        transportA.openSocket()

        val fakePeer = DatagramSocket(0)
        val fakePeerAddress = InetSocketAddress("127.0.0.1", fakePeer.localPort)
        val receivedSeqs = CopyOnWriteArrayList<Long>()
        val fakeThread = thread {
            val buffer = ByteArray(2048)
            var helloReplied = false
            while (!fakePeer.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    fakePeer.receive(packet)
                } catch (e: Exception) {
                    break
                }
                // Fake peer replies to the handshake but deliberately never ACKs audio,
                // so the sender must keep retransmitting from its unacknowledged window.
                when (val frame = LanProtocol.decodeFrame(packet.data.copyOf(packet.length))) {
                    is LanFrame.Hello -> {
                        if (!helloReplied) {
                            helloReplied = true
                            val reply = LanProtocol.encodeFrame(LanFrame.Hello("peer-b", -1))
                            fakePeer.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        }
                    }
                    is LanFrame.Audio -> receivedSeqs.add(frame.seq)
                    else -> Unit
                }
            }
        }

        transportA.start("peer-a", "peer-b", listOf(fakePeerAddress))
        withTimeout(3_000) {
            while (peerLinkA.state.value !is PeerLink.LinkState.Connected) delay(20)
        }

        peerLinkA.send(byteArrayOf(7))

        withTimeout(3_000) {
            while (receivedSeqs.count { it == 0L } < 2) delay(20)
        }
        assertTrue("frame should have been sent, then retransmitted", receivedSeqs.count { it == 0L } >= 2)

        transportA.stop()
        fakePeer.close()
        fakeThread.join(1_000)
        peerLinkA.clear()
        scopeA.cancel()
    }
}
