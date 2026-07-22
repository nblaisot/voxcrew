package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleInterfaceTest {

    @Test
    fun `detects tailscale cgnat range`() {
        assertTrue(TailscaleInterface.isTailscaleAddress("100.64.0.1"))
        assertTrue(TailscaleInterface.isTailscaleAddress("100.127.255.254"))
        assertFalse(TailscaleInterface.isTailscaleAddress("192.168.0.1"))
        assertFalse(TailscaleInterface.isTailscaleAddress("10.0.0.1"))
    }

    @Test
    fun `select prefers tun0 over tun1`() {
        val chosen = TailscaleInterface.selectLocalOverlayIpv4(
            listOf(
                TailscaleInterface.OverlayCandidate("wlan0", "192.168.1.2"),
                TailscaleInterface.OverlayCandidate("rmnet", "100.90.168.107"),
                TailscaleInterface.OverlayCandidate("tun1", "100.107.118.93"),
                TailscaleInterface.OverlayCandidate("tun0", "100.64.0.1"),
            ),
        )
        assertEquals("100.64.0.1", chosen)
    }

    @Test
    fun `select falls back to non-tun 100-x when no tun`() {
        val chosen = TailscaleInterface.selectLocalOverlayIpv4(
            listOf(
                TailscaleInterface.OverlayCandidate("vpn1", "100.90.1.2"),
                TailscaleInterface.OverlayCandidate("vpn0", "100.80.1.2"),
            ),
        )
        // Stable by iface name then address → vpn0
        assertEquals("100.80.1.2", chosen)
    }

    @Test
    fun `select returns null without tailscale addresses`() {
        assertNull(
            TailscaleInterface.selectLocalOverlayIpv4(
                listOf(TailscaleInterface.OverlayCandidate("wlan0", "192.168.1.2")),
            ),
        )
    }
}
