package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayEndpointCacheTest {

    @Test
    fun `harvestFromBeacon stores overlayHost and port`() {
        val cache = OverlayEndpointCache()
        cache.harvestFromBeacon(
            LanPeer(
                uid = "a",
                displayName = "A",
                host = "192.168.1.2",
                port = 47101,
                lastSeenMs = 1L,
                overlayHost = "100.64.0.2",
            ),
        )
        assertEquals(OverlayEndpoint("100.64.0.2", 47101), cache.get("a"))
    }

    @Test
    fun `harvestFromBeacon ignores peers without overlayHost`() {
        val cache = OverlayEndpointCache()
        cache.harvestFromBeacon(
            LanPeer("a", "A", "192.168.1.2", 47101, 1L, overlayHost = null),
        )
        assertNull(cache.get("a"))
    }

    @Test
    fun `overlayDialTarget prefers beacon metadata over cache`() {
        val sighting = LanPeer(
            uid = "a",
            displayName = "A",
            host = "192.168.1.2",
            port = 47101,
            lastSeenMs = 9L,
            overlayHost = "100.64.0.9",
        )
        val dial = overlayDialTarget(
            uid = "a",
            displayName = "A",
            sighting = sighting,
            cached = OverlayEndpoint("100.64.0.1", 47101),
            overlayNetworkPresent = true,
        )
        assertEquals("100.64.0.9", dial?.host)
        assertTrue(dial?.viaOverlay == true)
    }

    @Test
    fun `overlayDialTarget uses cache only when local overlay is up`() {
        val cached = OverlayEndpoint("100.90.1.2", 47101)
        assertNull(
            overlayDialTarget(
                uid = "a",
                displayName = "A",
                sighting = null,
                cached = cached,
                overlayNetworkPresent = false,
            ),
        )
        val dial = overlayDialTarget(
            uid = "a",
            displayName = "A",
            sighting = null,
            cached = cached,
            overlayNetworkPresent = true,
        )
        assertEquals("100.90.1.2", dial?.host)
        assertEquals(47101, dial?.port)
        assertTrue(dial?.viaOverlay == true)
    }

    @Test
    fun `put rejects invalid ports`() {
        val cache = OverlayEndpointCache()
        cache.put("a", "100.64.0.1", 0)
        assertNull(cache.get("a"))
        cache.put("a", "100.64.0.1", 47101)
        assertEquals(OverlayEndpoint("100.64.0.1", 47101), cache.get("a"))
    }
}
