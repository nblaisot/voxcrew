package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLinkDeathPolicyTest {

    @Test
    fun `promotes overlay when LAN sighting is absent`() {
        assertTrue(LocalLinkDeathPolicy.shouldPromoteOverlay(null, nowMs = 10_000L))
    }

    @Test
    fun `promotes overlay when LAN sighting is stale`() {
        val lan = LanPeer(
            uid = "a",
            displayName = "A",
            host = "192.168.1.2",
            port = LanTcpServer.TCP_PORT,
            lastSeenMs = 1_000L,
            viaOverlay = false,
        )
        val now = 1_000L + LanBeacon.STALE_MS + 1
        assertTrue(LocalLinkDeathPolicy.shouldPromoteOverlay(lan, nowMs = now))
    }

    @Test
    fun `keeps LAN when sighting is fresh`() {
        val now = 20_000L
        val lan = LanPeer(
            uid = "a",
            displayName = "A",
            host = "192.168.1.2",
            port = LanTcpServer.TCP_PORT,
            lastSeenMs = now - 1_000L,
            viaOverlay = false,
        )
        assertFalse(LocalLinkDeathPolicy.shouldPromoteOverlay(lan, nowMs = now))
    }

    @Test
    fun `promotes when lastSeen is zero registry-style`() {
        val lan = LanPeer(
            uid = "a",
            displayName = "A",
            host = "192.168.1.2",
            port = LanTcpServer.TCP_PORT,
            lastSeenMs = 0L,
            viaOverlay = false,
        )
        assertTrue(LocalLinkDeathPolicy.shouldPromoteOverlay(lan, nowMs = 10_000L))
    }
}
