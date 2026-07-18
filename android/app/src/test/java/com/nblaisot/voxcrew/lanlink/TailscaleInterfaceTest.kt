package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertFalse
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
}
