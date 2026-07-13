package com.nblaisot.voxcrew.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttReadinessTest {
    @Test
    fun `PTT stays disabled until the foreground duplex route is ready`() {
        assertFalse(pttEnabled(appForeground = false, audioRouteReady = true))
        assertFalse(pttEnabled(appForeground = true, audioRouteReady = false))
        assertTrue(pttEnabled(appForeground = true, audioRouteReady = true))
    }

    @Test
    fun `VOX permission and fatal failures disable PTT`() {
        assertFalse(pttEnabled(voxEnabled = true))
        assertFalse(pttEnabled(micPermissionGranted = false))
        assertFalse(pttEnabled(audioStartAllowed = false))
    }

    private fun pttEnabled(
        voxEnabled: Boolean = false,
        appForeground: Boolean = true,
        micPermissionGranted: Boolean = true,
        audioRouteReady: Boolean = true,
        audioStartAllowed: Boolean = true,
    ) = computePttEnabled(
        voxEnabled = voxEnabled,
        appForeground = appForeground,
        micPermissionGranted = micPermissionGranted,
        audioRouteReady = audioRouteReady,
        audioStartAllowed = audioStartAllowed,
    )
}
