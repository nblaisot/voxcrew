package com.nblaisot.voxcrew.lanlink

import java.io.IOException
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTcpClientSingleFlightTest {
    @Test
    fun `duplicate reconciliation requests share one dial and one retry deadline`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val attempts = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val allowFailure = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val peerLink = PeerLink(scope)
        peerLink.resetFor("peer")
        val client = LanTcpClient(
            scope = scope,
            localUid = "local",
            peerLink = peerLink,
            server = LanTcpServer(scope),
            networkSocketBinder = NoOpTestNetworkBinder,
            inboundRouteResolver = { null },
            socketFactory = {
                val attempt = attempts.incrementAndGet()
                if (attempt > 1) secondStarted.countDown()
                object : Socket() {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        if (attempt == 1) {
                            firstStarted.countDown()
                            allowFailure.await(2, TimeUnit.SECONDS)
                        }
                        throw IOException("synthetic failure")
                    }
                }
            },
        )
        val target = LanPeer(
            uid = "peer",
            displayName = "Peer",
            host = "192.168.1.9",
            port = 47101,
            lastSeenMs = 0L,
            viaOverlay = false,
        ).routed()

        repeat(20) { client.setTarget(target) }

        assertTrue("first dial did not start", firstStarted.await(2, TimeUnit.SECONDS))
        assertEquals(1, attempts.get())
        allowFailure.countDown()
        assertFalse(
            "a duplicate trigger bypassed the failure deadline",
            secondStarted.await(300, TimeUnit.MILLISECONDS),
        )
        assertEquals(1, client.failureCountForTest(target))

        client.setTarget(null)
        scope.cancel()
    }
}
