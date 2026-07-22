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
            isStillWanted = { true },
            overlayPeerProvider = { overlay },
            lanPeerProvider = { null },
        )
        conn.start()
        conn.promoteToOverlay(overlay)
        conn.promoteToOverlay(overlay)
        conn.promoteToOverlay(overlay)
        conn.stop()
    }

    @Test
    fun `USE_LAN clears lanDialFailed after overlay-only path`() {
        val conn = PeerConnection(
            peerUid = "peer",
            scope = scope,
            localUid = "local",
            lanServer = server,
            isStillWanted = { true },
            overlayPeerProvider = { overlay },
            lanPeerProvider = { lan },
        )
        conn.start()
        conn.applyPathTargets(lanPeer = null, overlayPeer = overlay)
        assertFalse(conn.lanDialFailedForTest())
        assertFalse(LocalLinkDeathPolicy.shouldPromoteOverlay(lan))
        assertTrue(LocalLinkDeathPolicy.shouldPromoteOverlay(null))
        conn.applyPathTargets(lanPeer = lan, overlayPeer = overlay)
        assertFalse(conn.lanDialFailedForTest())
        conn.stop()
    }
}
