package com.nblaisot.voxcrew.lanlink

import com.nblaisot.voxcrew.connectivity.NetworkSocketBinder
import com.nblaisot.voxcrew.connectivity.OverlayNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramSocket
import java.net.Socket

class LanBeaconOverlayTest {

    @Test
    fun `advertised overlay host follows verified connectivity state exactly`() {
        val beacon = LanBeacon(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        // Tailscale not up yet at start: nothing to announce.
        assertNull(beacon.announcedOverlayHostForTest())

        beacon.updateOverlayNetwork(OverlayNetwork(7L, "tun1", "100.64.0.7"))
        assertEquals("100.64.0.7", beacon.announcedOverlayHostForTest())

        // A lost overlay is no longer advertised as if it were live.
        beacon.updateOverlayNetwork(null)
        assertNull(beacon.announcedOverlayHostForTest())

        beacon.updateOverlayNetwork(OverlayNetwork(8L, "tun2", "100.64.0.8"))
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

    @Test
    fun `overlay sender is bound to verified VPN network`() {
        val binder = RecordingBinder()
        val beacon = LanBeacon(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            networkSocketBinder = binder,
        )

        beacon.updateOverlayNetwork(OverlayNetwork(7L, "tun1", "100.64.0.7"))

        assertEquals(listOf(7L), binder.datagramHandles)
    }

    private class RecordingBinder : NetworkSocketBinder {
        val datagramHandles = mutableListOf<Long>()

        override fun bindSocket(networkHandle: Long, socket: Socket) = Unit

        override fun bindSocket(networkHandle: Long, socket: DatagramSocket) {
            datagramHandles += networkHandle
        }
    }
}
