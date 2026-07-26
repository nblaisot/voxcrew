package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Test

class LanBeaconScheduleTest {
    @Test
    fun `same UUID replaces transient endpoint without duplicate`() {
        val first = LanPeer(
            uid = "peer-b",
            displayName = "Bob",
            host = "192.168.1.2",
            port = 47_101,
            lastSeenMs = 1L,
        )
        val updated = first.copy(
            displayName = "Bob 2",
            host = "192.168.1.3",
            port = 47_102,
            lastSeenMs = 2L,
        )

        val presence = mutableMapOf<String, LanPeer>()
        upsertPresence(presence, first)
        upsertPresence(presence, updated)

        assertEquals(1, presence.size)
        assertEquals(updated, presence["peer-b"])
    }

    @Test
    fun `stale UUID is removed unless TCP session remains connected`() {
        val stale = LanPeer("stale", "Stale", "192.168.1.2", 47_101, 1L)
        val connected = LanPeer("connected", "Connected", "192.168.1.3", 47_101, 1L)

        val presence = prunePresence(
            current = mapOf(stale.uid to stale, connected.uid to connected),
            connectedUids = setOf(connected.uid),
            nowMs = LanBeacon.STALE_MS + 2L,
            staleMs = LanBeacon.STALE_MS,
        )

        assertEquals(setOf("connected"), presence.keys)
    }
}
