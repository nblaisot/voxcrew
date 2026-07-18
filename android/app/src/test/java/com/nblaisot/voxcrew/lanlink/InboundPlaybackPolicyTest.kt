package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Test

class InboundPlaybackPolicyTest {
    @Test
    fun `background and VOX off uses multimedia inbound`() {
        assertEquals(
            InboundPlaybackMode.MEDIA,
            InboundPlaybackPolicy.mode(appForeground = false, voxEnabled = false),
        )
    }

    @Test
    fun `foreground always uses Telecom duplex path`() {
        assertEquals(
            InboundPlaybackMode.TELECOM,
            InboundPlaybackPolicy.mode(appForeground = true, voxEnabled = false),
        )
        assertEquals(
            InboundPlaybackMode.TELECOM,
            InboundPlaybackPolicy.mode(appForeground = true, voxEnabled = true),
        )
    }

    @Test
    fun `background VOX keeps Telecom duplex path`() {
        assertEquals(
            InboundPlaybackMode.TELECOM,
            InboundPlaybackPolicy.mode(appForeground = false, voxEnabled = true),
        )
    }
}
