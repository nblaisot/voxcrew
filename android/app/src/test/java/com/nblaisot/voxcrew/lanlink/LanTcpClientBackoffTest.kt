package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTcpClientBackoffTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val server = LanTcpServer(scope)
    private val peerLink = PeerLink(scope)
    private val client = LanTcpClient(
        scope,
        "a",
        peerLink,
        server,
        NoOpTestNetworkBinder,
        inboundRouteResolver = { null },
    )

    private val lanPeer = LanPeer("b", "B", "192.168.1.2", 1234, lastSeenMs = 0L, viaOverlay = false)
    private val overlayPeer = LanPeer("b", "B", "100.64.0.2", 1234, lastSeenMs = 0L, viaOverlay = true)
    private val lanTarget get() = lanPeer.routed()
    private val overlayTarget get() = overlayPeer.routed()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val first = client.backoffDelayMs(lanTarget)
        repeat(3) { client.recordDialFailureForTest() }
        val afterThree = client.backoffDelayMs(lanTarget)
        assertEquals(first * 8, afterThree)

        repeat(20) { client.recordDialFailureForTest() }
        assertEquals(LanTcpClient.MAX_RETRY_DELAY_MS, client.backoffDelayMs(lanTarget))
        assertEquals(LanTcpClient.MAX_RETRY_DELAY_MS, client.backoffDelayMs(overlayTarget))
    }

    @Test
    fun `user action resets backoff to the base delay`() {
        repeat(10) { client.recordDialFailureForTest() }
        assertTrue(client.backoffDelayMs(lanTarget) > 1_000L)

        client.resetDialBackoff()

        assertEquals(500L, client.backoffDelayMs(lanTarget))
        assertEquals(250L, client.backoffDelayMs(overlayTarget))
    }

    @Test
    fun `same endpoint with newer lastSeen does not reset backoff`() {
        peerLink.resetFor("b")
        client.setTarget(lanPeer.copy(lastSeenMs = 1_000L).routed())
        repeat(3) { client.recordDialFailureForTest() }
        val afterFailures = client.backoffDelayMs(lanTarget)

        client.setTarget(lanPeer.copy(lastSeenMs = 2_000L).routed())

        assertEquals(afterFailures, client.backoffDelayMs(lanTarget))
    }

    @Test
    fun `host change resets backoff`() {
        peerLink.resetFor("b")
        client.setTarget(lanPeer.copy(lastSeenMs = 1_000L).routed())
        repeat(5) { client.recordDialFailureForTest() }
        assertTrue(client.backoffDelayMs(lanTarget) > 500L)

        client.setTarget(lanPeer.copy(host = "192.168.1.99", lastSeenMs = 2_000L).routed())

        assertEquals(
            500L,
            client.backoffDelayMs(lanPeer.copy(host = "192.168.1.99").routed()),
        )
    }

    @Test
    fun `intentional cancel generation skips failure accounting`() {
        val gen = client.cancelGenerationForTest()
        org.junit.Assert.assertFalse(client.isCancelledAttempt(gen))
        client.bumpCancelGenerationForTest()
        assertTrue(client.isCancelledAttempt(gen))
    }

    @Test
    fun `fixed tcp listen port is documented next to beacon`() {
        assertEquals(47101, LanTcpServer.TCP_PORT)
        assertEquals(47100, LanBeacon.BEACON_PORT)
    }
}
