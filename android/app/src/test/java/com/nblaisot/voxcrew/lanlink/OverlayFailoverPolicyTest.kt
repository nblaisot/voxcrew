package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayFailoverPolicyTest {

    @Test
    fun `preferLan keeps non-overlay sighting over Tailscale`() {
        val lan = LanPeer("a", "A", "192.168.1.2", 1, 0L, viaOverlay = false)
        val overlay = LanPeer("a", "A", "100.64.0.2", 1, 0L, viaOverlay = true)
        assertEquals(lan, OverlayFailoverPolicy.preferLan(lan, overlay))
        assertEquals(overlay, OverlayFailoverPolicy.preferLan(null, overlay))
    }

    @Test
    fun `decide prefers LAN when lan sighting is live and dials have not failed`() {
        val lan = LanPeer("a", "A", "192.168.1.2", 1, 9_500L, viaOverlay = false)
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = lan,
            hasOverlayEndpoint = true,
            activeVia = PathLabels.VPN,
            sessionHealthy = true,
            lanDialFailed = false,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_LAN, decision.action)
    }

    @Test
    fun `decide uses overlay when LAN dial failed even if LAN beacon still present`() {
        val lan = LanPeer("a", "A", "192.168.1.2", 1, 9_500L, viaOverlay = false)
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = lan,
            hasOverlayEndpoint = true,
            activeVia = null,
            sessionHealthy = false,
            lanDialFailed = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_OVERLAY, decision.action)
    }

    @Test
    fun `decide keeps trying LAN when dial failed but no overlay is known`() {
        val lan = LanPeer("a", "A", "192.168.1.2", 1, 9_500L, viaOverlay = false)
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = lan,
            hasOverlayEndpoint = false,
            activeVia = null,
            sessionHealthy = false,
            lanDialFailed = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_LAN, decision.action)
    }

    @Test
    fun `decide uses overlay when LAN absent and endpoint known`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = true,
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
            activeVia = PathLabels.LOCAL,
            sessionHealthy = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.KEEP_SESSION, decision.action)
    }

    @Test
    fun `decide uses overlay when LAN session is dead and endpoint known`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = true,
            activeVia = PathLabels.LOCAL,
            sessionHealthy = false,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_OVERLAY, decision.action)
    }

    @Test
    fun `decide keeps healthy overlay session when discovery is quiet`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = false,
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
            activeVia = null,
            sessionHealthy = false,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.CLEAR, decision.action)
    }

    @Test
    fun `decide uses cloud when LAN and overlay absent but relay ready`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = false,
            activeVia = null,
            sessionHealthy = false,
            hasCloudEndpoint = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_CLOUD, decision.action)
    }

    @Test
    fun `decide prefers overlay over cloud`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = true,
            activeVia = null,
            sessionHealthy = false,
            hasCloudEndpoint = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_OVERLAY, decision.action)
    }

    @Test
    fun `decide uses cloud after LAN dial failed when no overlay`() {
        val lan = LanPeer("a", "A", "192.168.1.2", 1, 9_500L, viaOverlay = false)
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = lan,
            hasOverlayEndpoint = false,
            activeVia = null,
            sessionHealthy = false,
            lanDialFailed = true,
            hasCloudEndpoint = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_CLOUD, decision.action)
    }

    @Test
    fun `decide keeps healthy cloud session when discovery is quiet`() {
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = null,
            hasOverlayEndpoint = false,
            activeVia = PathLabels.CLOUD,
            sessionHealthy = true,
            hasCloudEndpoint = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.KEEP_SESSION, decision.action)
    }

    @Test
    fun `decide promotes to LAN from cloud when LAN sighting returns`() {
        val lan = LanPeer("a", "A", "192.168.1.2", 1, 9_500L, viaOverlay = false)
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = lan,
            hasOverlayEndpoint = false,
            activeVia = PathLabels.CLOUD,
            sessionHealthy = true,
            hasCloudEndpoint = true,
        )
        assertEquals(OverlayFailoverPolicy.PathAction.USE_LAN, decision.action)
    }

    @Test
    fun `discovery cadence is for join not failover heartbeat`() {
        assertTrue(LanBeacon.BROADCAST_INTERVAL_MS >= 3_000L)
        assertTrue(LanBeacon.STALE_MS >= LanBeacon.BROADCAST_INTERVAL_MS * 2)
    }
}
