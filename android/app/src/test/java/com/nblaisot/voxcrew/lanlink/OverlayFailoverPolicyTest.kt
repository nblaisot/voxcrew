package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayFailoverPolicyTest {

    @Test
    fun `warms standby after one missed announce while still listed`() {
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
    fun `decide prefers LAN when lan sighting is live`() {
        val lan = LanPeer("a", "A", "192.168.1.2", 1, 9_500L, viaOverlay = false)
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = lan,
            hasOverlayEndpoint = true,
            nowMs = 10_000L,
            activeVia = PathLabels.VPN,
            sessionHealthy = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_LAN, decision.action)
    }

    @Test
    fun `decide uses overlay when LAN absent and endpoint known`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = true,
            nowMs = 10_000L,
            activeVia = null,
            sessionHealthy = false,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_OVERLAY, decision.action)
    }

    @Test
    fun `decide never tears healthy LAN session for overlay`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = true,
            nowMs = 10_000L,
            activeVia = PathLabels.LOCAL,
            sessionHealthy = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.KEEP_SESSION, decision.action)
        assertTrue(decision.warmStandby)
    }

    @Test
    fun `decide keeps healthy LAN session without warming when no overlay endpoint`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = false,
            nowMs = 10_000L,
            activeVia = PathLabels.LOCAL,
            sessionHealthy = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.KEEP_SESSION, decision.action)
        assertFalse(decision.warmStandby)
    }

    @Test
    fun `decide uses overlay when LAN session is dead and endpoint known`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = true,
            nowMs = 10_000L,
            activeVia = PathLabels.LOCAL,
            sessionHealthy = false,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_OVERLAY, decision.action)
    }

    @Test
    fun `decide keeps healthy session when discovery is quiet`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = false,
            nowMs = 10_000L,
            activeVia = PathLabels.VPN,
            sessionHealthy = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.KEEP_SESSION, decision.action)
    }

    @Test
    fun `decide clears only when discovery and session are both gone`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = false,
            nowMs = 10_000L,
            activeVia = null,
            sessionHealthy = false,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.CLEAR, decision.action)
    }

    @Test
    fun `discovery cadence is for join not failover heartbeat`() {
        assertTrue(LanBeacon.BROADCAST_INTERVAL_MS >= 3_000L)
        assertTrue(LanBeacon.STALE_MS >= LanBeacon.BROADCAST_INTERVAL_MS * 2)
        assertEquals(LanBeacon.BROADCAST_INTERVAL_MS, LanBeacon.MISSED_BEACON_MS)
    }
}
