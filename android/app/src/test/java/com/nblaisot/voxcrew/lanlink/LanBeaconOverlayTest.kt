package com.nblaisot.voxcrew.lanlink

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanBeaconOverlayTest {

    @Test
    fun `overlay host appearing after start is announced and survives a transient dropout`() {
        var resolved: String? = null
        val beacon = LanBeacon(
            context = mockk<Context>(relaxed = true),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            overlayHostResolver = { resolved },
        )

        // Tailscale not up yet at start: nothing to announce.
        assertNull(beacon.announcedOverlayHostForTest())

        // Overlay comes up later: announced within the next broadcast tick.
        resolved = "100.64.0.7"
        assertEquals("100.64.0.7", beacon.announcedOverlayHostForTest())

        // Momentary dropout: the node-stable last-known address keeps being announced.
        resolved = null
        assertEquals("100.64.0.7", beacon.announcedOverlayHostForTest())

        // Address change is picked up live.
        resolved = "100.64.0.8"
        assertEquals("100.64.0.8", beacon.announcedOverlayHostForTest())
    }

    @Test
    fun `beacon v1 payload round trips without overlay host`() {
        val bytes = LanBeacon.encodeForTest("uid-a", "Alice", 51234)
        val decoded = LanBeacon.decodeForTest(bytes)
        requireNotNull(decoded)
        assertEquals("uid-a", decoded.uid)
        assertEquals("Alice", decoded.displayName)
        assertEquals(51234, decoded.port)
        assertNull(decoded.overlayHost)
    }

    @Test
    fun `beacon payload includes optional overlay host`() {
        val bytes = LanBeacon.encodeForTest("uid-b", "Bob", 51235, "100.64.0.2")
        val decoded = LanBeacon.decodeForTest(bytes)
        requireNotNull(decoded)
        assertEquals("100.64.0.2", decoded.overlayHost)
    }
}
