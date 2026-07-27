package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `new LAN route identity clears lanDialFailed after prior failure`() {
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
        // Fail Local then lock onto overlay.
        conn.applyPathTargets(lanPeer = lanTarget, overlayPeer = overlayTarget)
        conn.onNetworksInvalidated(setOf(lanTarget.route.networkHandle))
        // SoftAP-style unbound route for same host must clear suppression and prefer Local.
        val unbound = lan.copy(host = "192.168.1.5").routed(LocalLanNetworks.UNBOUND_NETWORK_HANDLE)
        conn.applyPathTargets(lanPeer = unbound, overlayPeer = overlayTarget)
        assertFalse(conn.lanDialFailedForTest())
        assertEquals(PeerPath.LAN, conn.targetPathForTest())
        conn.stop()
    }

    @Test
    fun `overlay invalidation immediately selects LAN fallback target`() {
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

        conn.onNetworksInvalidated(setOf(overlayTarget.route.networkHandle))

        assertEquals(PeerPath.LAN, conn.targetPathForTest())
        assertFalse(conn.lanDialFailedForTest())
        conn.stop()
    }

    @Test
    fun `overlay invalidation without LAN fallback clears VPN target`() {
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
        conn.applyPathTargets(lanPeer = null, overlayPeer = overlayTarget)

        conn.onNetworksInvalidated(setOf(overlayTarget.route.networkHandle))

        assertNull(conn.targetPathForTest())
        assertFalse(conn.lanDialFailedForTest())
        conn.stop()
    }
}
