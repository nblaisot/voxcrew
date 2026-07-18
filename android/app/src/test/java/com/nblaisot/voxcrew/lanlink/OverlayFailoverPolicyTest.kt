package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayFailoverPolicyTest {

    @Test
    fun `warms standby after one missed beacon while still listed`() {
        val now = 10_000L
        assertTrue(
            OverlayFailoverPolicy.shouldWarmStandby(
                lanLastSeenMs = now - LanBeacon.MISSED_BEACON_MS,
                nowMs = now,
                hasOverlayEndpoint = true,
                lanStillListed = true,
            ),
        )
    }

    @Test
    fun `does not warm standby while beacons are fresh`() {
        val now = 10_000L
        assertFalse(
            OverlayFailoverPolicy.shouldWarmStandby(
                lanLastSeenMs = now - 200L,
                nowMs = now,
                hasOverlayEndpoint = true,
                lanStillListed = true,
            ),
        )
    }

    @Test
    fun `promotes overlay only when LAN is gone and endpoint is known`() {
        assertTrue(OverlayFailoverPolicy.shouldPromoteOverlay(lanStillListed = false, hasOverlayEndpoint = true))
        assertFalse(OverlayFailoverPolicy.shouldPromoteOverlay(lanStillListed = true, hasOverlayEndpoint = true))
        assertFalse(OverlayFailoverPolicy.shouldPromoteOverlay(lanStillListed = false, hasOverlayEndpoint = false))
    }

    @Test
    fun `preferLan keeps non-overlay sighting over Tailscale`() {
        val lan = LanPeer("a", "A", "192.168.1.2", 1, 0L, viaOverlay = false)
        val overlay = LanPeer("a", "A", "100.64.0.2", 1, 0L, viaOverlay = true)
        assertEquals(lan, OverlayFailoverPolicy.preferLan(lan, overlay))
        assertEquals(overlay, OverlayFailoverPolicy.preferLan(null, overlay))
    }

    @Test
    fun `beacon stale window is under five seconds`() {
        assertTrue(LanBeacon.STALE_MS <= 2_500L)
        assertTrue(LanBeacon.BROADCAST_INTERVAL_MS <= 1_000L)
        assertTrue(LanBeacon.STALE_MS + LanBeacon.PRUNE_INTERVAL_MS < 5_000L)
    }
}
