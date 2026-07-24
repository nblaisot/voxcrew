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

    private class RecordingBinder : NetworkSocketBinder {
        val socketHandles = mutableListOf<Long>()

        override fun bindSocket(networkHandle: Long, socket: Socket) {
            socketHandles += networkHandle
        }

        override fun bindSocket(networkHandle: Long, socket: DatagramSocket) = Unit
    }
}
