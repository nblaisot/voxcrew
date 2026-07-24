package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerConnectionPromoteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val server = LanTcpServer(scope)

    private val overlay = LanPeer(
        uid = "peer",
        displayName = "Peer",
        host = "100.64.0.2",
        port = LanTcpServer.TCP_PORT,
        lastSeenMs = 0L,
        viaOverlay = true,
    )

    private val lan = LanPeer(
        uid = "peer",
        displayName = "Peer",
        host = "192.168.1.5",
        port = LanTcpServer.TCP_PORT,
        lastSeenMs = System.currentTimeMillis(),
        viaOverlay = false,
    )
    private val overlayTarget get() = overlay.routed()
    private val lanTarget get() = lan.routed()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `promoteToOverlay repeated ticks do not throw`() {
        val conn = PeerConnection(
            peerUid = "peer",
            scope = scope,
            localUid = "local",
            lanServer = server,
            networkSocketBinder = NoOpTestNetworkBinder,
            inboundRouteResolver = { null },
            isStillWanted = { true },
            overlayPeerProvider = { overlayTarget },
            lanPeerProvider = { null },
        )
        conn.start()
        conn.promoteToOverlay(overlayTarget)
        conn.promoteToOverlay(overlayTarget)
        conn.promoteToOverlay(overlayTarget)
        conn.stop()
    }

    @Test
    fun `USE_LAN clears lanDialFailed after overlay-only path`() {
        val conn = PeerConnection(
            peerUid = "peer",
            scope = scope,
            localUid = "local",
            lanServer = server,
            networkSocketBinder = NoOpTestNetworkBinder,
            inboundRouteResolver = { null },
            isStillWanted = { true },
            overlayPeerProvider = { overlayTarget },
            lanPeerProvider = { lanTarget },
        )
        conn.start()
        conn.applyPathTargets(lanPeer = null, overlayPeer = overlayTarget)
        assertFalse(conn.lanDialFailedForTest())
        assertFalse(LocalLinkDeathPolicy.shouldPromoteOverlay(lan))
        assertTrue(LocalLinkDeathPolicy.shouldPromoteOverlay(null))
        conn.applyPathTargets(lanPeer = lanTarget, overlayPeer = overlayTarget)
        assertFalse(conn.lanDialFailedForTest())
        conn.stop()
    }
}
