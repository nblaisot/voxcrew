package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanBeaconOverlayTest {

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
