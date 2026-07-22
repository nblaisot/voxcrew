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
    private val client = LanTcpClient(scope, "a", peerLink, server)

    private val lanPeer = LanPeer("b", "B", "192.168.1.2", 1234, lastSeenMs = 0L, viaOverlay = false)
    private val overlayPeer = LanPeer("b", "B", "100.64.0.2", 1234, lastSeenMs = 0L, viaOverlay = true)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val first = client.backoffDelayMs(lanPeer)
        repeat(3) { client.recordDialFailureForTest() }
        val afterThree = client.backoffDelayMs(lanPeer)
        assertEquals(first * 8, afterThree)

        repeat(20) { client.recordDialFailureForTest() }
        assertEquals(LanTcpClient.MAX_RETRY_DELAY_MS, client.backoffDelayMs(lanPeer))
        assertEquals(LanTcpClient.MAX_RETRY_DELAY_MS, client.backoffDelayMs(overlayPeer))
    }

    @Test
    fun `user action resets backoff to the base delay`() {
        repeat(10) { client.recordDialFailureForTest() }
        assertTrue(client.backoffDelayMs(lanPeer) > 1_000L)

        client.resetDialBackoff()

        assertEquals(500L, client.backoffDelayMs(lanPeer))
        assertEquals(250L, client.backoffDelayMs(overlayPeer))
    }

    @Test
    fun `same endpoint with newer lastSeen does not reset backoff`() {
        peerLink.resetFor("b")
        client.setTarget(lanPeer.copy(lastSeenMs = 1_000L))
        repeat(3) { client.recordDialFailureForTest() }
        val afterFailures = client.backoffDelayMs(lanPeer)

        client.setTarget(lanPeer.copy(lastSeenMs = 2_000L))

        assertEquals(afterFailures, client.backoffDelayMs(lanPeer))
    }

    @Test
    fun `host change resets backoff`() {
        peerLink.resetFor("b")
        client.setTarget(lanPeer.copy(lastSeenMs = 1_000L))
        repeat(5) { client.recordDialFailureForTest() }
        assertTrue(client.backoffDelayMs(lanPeer) > 500L)

        client.setTarget(lanPeer.copy(host = "192.168.1.99", lastSeenMs = 2_000L))

        assertEquals(500L, client.backoffDelayMs(lanPeer.copy(host = "192.168.1.99")))
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
