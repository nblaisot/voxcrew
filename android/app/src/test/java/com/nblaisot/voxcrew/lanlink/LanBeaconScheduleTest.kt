package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanBeaconScheduleTest {
    @Test
    fun `disconnected peers keep legacy discovery cadence`() {
        assertEquals(
            LanBeacon.BROADCAST_INTERVAL_MS,
            steadyAnnouncementIntervalMs(hasDisconnectedPeer = true),
        )
    }

    @Test
    fun `healthy mesh uses slow safety announcement`() {
        assertEquals(
            LanBeacon.CONNECTED_SAFETY_INTERVAL_MS,
            steadyAnnouncementIntervalMs(hasDisconnectedPeer = false),
        )
    }

    @Test
    fun `connected peer stays visible while path sighting expires`() {
        val remembered = LanPeer(
            uid = "peer-b",
            displayName = "Bob",
            host = "192.168.1.2",
            port = 47101,
            lastSeenMs = 1L,
        )

        val peers = mergedBeaconPeers(
            lan = emptyMap(),
            overlay = emptyMap(),
            connectedPaths = mapOf("peer-b" to true),
            lastKnown = mapOf("peer-b" to remembered),
        )

        assertEquals(listOf("peer-b"), peers.map { it.uid })
        assertTrue(peers.single().viaOverlay)
    }
}
