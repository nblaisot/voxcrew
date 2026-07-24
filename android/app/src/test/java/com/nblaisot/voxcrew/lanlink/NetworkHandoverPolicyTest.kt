package com.nblaisot.voxcrew.lanlink

import com.nblaisot.voxcrew.connectivity.ConnectivitySnapshot
import com.nblaisot.voxcrew.connectivity.Ipv4Link
import com.nblaisot.voxcrew.connectivity.LanNetwork
import com.nblaisot.voxcrew.connectivity.NetworkSocketBinder
import com.nblaisot.voxcrew.connectivity.OverlayNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class NetworkHandoverPolicyTest {
    private val lanPeer = LanPeer(
        uid = "peer",
        displayName = "Peer",
        host = "192.168.86.231",
        port = 47101,
        lastSeenMs = 1L,
    )
    private val overlayPeer = lanPeer.copy(
        host = "100.65.176.46",
        overlayHost = "100.65.176.46",
        viaOverlay = true,
    )
    private val snapshot = ConnectivitySnapshot(
        lanNetworks = setOf(
            LanNetwork(609L, "wlan0", setOf(Ipv4Link("192.168.86.213", 24))),
        ),
        overlayNetwork = OverlayNetwork(610L, "tun1", "100.107.118.93"),
    )

    @Test
    fun `peer endpoints resolve only to explicit Android networks`() {
        assertEquals(609L, routePeer(lanPeer, snapshot)?.route?.networkHandle)
        assertEquals(610L, routePeer(overlayPeer, snapshot)?.route?.networkHandle)
        assertNull(routePeer(lanPeer.copy(host = "192.168.99.1"), snapshot))
        assertNull(routePeer(overlayPeer, snapshot.copy(overlayNetwork = null)))
    }

    @Test
    fun `overlapping LAN routes select deterministically`() {
        val overlapping = snapshot.copy(
            lanNetworks = setOf(
                LanNetwork(701L, "wlan1", setOf(Ipv4Link("192.168.86.10", 24))),
                LanNetwork(700L, "wlan0", setOf(Ipv4Link("192.168.86.11", 24))),
            ),
        )

        assertEquals(700L, routePeer(lanPeer, overlapping)?.route?.networkHandle)
    }

    @Test
    fun `socket binder receives routed handle before caller connects`() {
        val binder = RecordingBinder()
        val socket = Socket()
        val target = routePeer(overlayPeer, snapshot)!!

        bindTargetSocket(binder, target, socket)

        assertEquals(listOf(610L), binder.socketHandles)
        socket.close()
    }

    @Test
    fun `LAN loss invalidates LAN only and preserves overlay`() {
        val next = snapshot.copy(lanNetworks = emptySet())

        val invalidation = connectivityInvalidation(snapshot, next)

        assertEquals(setOf(609L), invalidation.lanHandles)
        assertTrue(invalidation.overlayHandles.isEmpty())
        assertEquals(snapshot.overlayNetwork, next.overlayNetwork)
    }

    @Test
    fun `overlay replacement invalidates overlay only and preserves LAN`() {
        val next = snapshot.copy(
            overlayNetwork = OverlayNetwork(700L, "tun2", "100.107.118.93"),
        )

        val invalidation = connectivityInvalidation(snapshot, next)

        assertTrue(invalidation.lanHandles.isEmpty())
        assertEquals(setOf(610L), invalidation.overlayHandles)
        assertEquals(snapshot.lanNetworks, next.lanNetworks)
    }

    @Test
    fun `disconnect transition immediately restores overlay probe target`() {
        val connected = overlayProbeTargets(
            overlayAvailable = true,
            localUid = "local",
            relevantUids = setOf("peer"),
            lanVisibleUids = emptySet(),
            connectedUids = setOf("peer"),
            endpointHosts = mapOf("peer" to "100.65.176.46"),
            seededHosts = emptyMap(),
        )
        val disconnected = overlayProbeTargets(
            overlayAvailable = true,
            localUid = "local",
            relevantUids = setOf("peer"),
            lanVisibleUids = emptySet(),
            connectedUids = emptySet(),
            endpointHosts = mapOf("peer" to "100.65.176.46"),
            seededHosts = emptyMap(),
        )

        assertTrue(connected.isEmpty())
        assertEquals(mapOf("peer" to "100.65.176.46"), disconnected)
    }

    @Test
    fun `same path dual dial converges on UID designated direction`() {
        assertTrue(
            shouldReplaceSession(
                localUid = "a",
                peerUid = "b",
                activePath = PeerPath.LAN,
                activeDirection = SessionDirection.INBOUND,
                candidatePath = PeerPath.LAN,
                candidateDirection = SessionDirection.OUTBOUND,
            ),
        )
        assertFalse(
            shouldReplaceSession(
                localUid = "b",
                peerUid = "a",
                activePath = PeerPath.LAN,
                activeDirection = SessionDirection.INBOUND,
                candidatePath = PeerPath.LAN,
                candidateDirection = SessionDirection.OUTBOUND,
            ),
        )
    }

    @Test
    fun `LAN candidate replaces VPN but VPN never replaces healthy LAN`() {
        assertTrue(
            shouldReplaceSession(
                "a",
                "b",
                PeerPath.OVERLAY,
                SessionDirection.OUTBOUND,
                PeerPath.LAN,
                SessionDirection.INBOUND,
            ),
        )
        assertFalse(
            shouldReplaceSession(
                "a",
                "b",
                PeerPath.LAN,
                SessionDirection.OUTBOUND,
                PeerPath.OVERLAY,
                SessionDirection.INBOUND,
            ),
        )
    }

    @Test
    fun `pruned LAN sighting can fall back to retained LAN endpoint`() {
        val retained = LanFallbackEndpoint(
            host = lanPeer.host,
            port = lanPeer.port,
            displayName = lanPeer.displayName,
        )

        val fallback = lanPeerForFallback("peer", sighting = null, fallback = retained)

        requireNotNull(fallback)
        assertEquals(lanPeer.host, fallback.host)
        assertEquals(PeerPath.LAN, routePeer(fallback, snapshot)?.route?.path)
    }

    @Test
    fun `live LAN sighting wins over retained endpoint`() {
        val stale = LanFallbackEndpoint(
            host = "192.168.86.99",
            port = lanPeer.port,
            displayName = "Old",
        )

        val selected = lanPeerForFallback("peer", sighting = lanPeer, fallback = stale)

        assertEquals(lanPeer.host, selected?.host)
    }

    @Test
    fun `retained LAN endpoint is rejected when current LAN cannot route it`() {
        val retained = LanFallbackEndpoint(
            host = "192.168.99.12",
            port = lanPeer.port,
            displayName = lanPeer.displayName,
        )

        val fallback = lanPeerForFallback("peer", sighting = null, fallback = retained)

        requireNotNull(fallback)
        assertNull(routePeer(fallback, snapshot))
    }

    @Test
    fun `detached invalidation resources close asynchronously`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val resources = DetachedNetworkResources(
            listOf {
                release.await(2, TimeUnit.SECONDS)
                closed.countDown()
            },
        )

        resources.closeAsync(scope, "test")

        assertFalse(closed.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        scope.cancel()
    }

    private class RecordingBinder : NetworkSocketBinder {
        val socketHandles = mutableListOf<Long>()

        override fun bindSocket(networkHandle: Long, socket: Socket) {
            socketHandles += networkHandle
        }

        override fun bindSocket(networkHandle: Long, socket: DatagramSocket) = Unit
    }
}
