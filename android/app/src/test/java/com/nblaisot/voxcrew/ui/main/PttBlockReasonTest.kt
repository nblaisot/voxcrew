package com.nblaisot.voxcrew.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttBlockReasonTest {
    @Test
    fun readyOnlyWhenDuplexAndRecipientReady() {
        assertEquals(PttBlockReason.Ready, reason())
        assertTrue(pttEnabledForReason(PttBlockReason.Ready))
    }

    @Test
    fun neverHoldToTalkWhenDisabled() {
        assertEquals(PttBlockReason.NoMic, reason(micPermissionGranted = false))
        assertEquals(PttBlockReason.Background, reason(appForeground = false))
        assertEquals(PttBlockReason.Pending, reason(audioRouteReady = false))
        assertEquals(PttBlockReason.Failed, reason(showAudioRetry = true))
        assertEquals(PttBlockReason.NoRecipient, reason(hasActiveRecipient = false))
        assertFalse(pttEnabledForReason(PttBlockReason.NoRecipient))
        assertFalse(pttEnabledForReason(PttBlockReason.Pending))
    }

    @Test
    fun transmittingWithoutLinkShowsNoLinkButKeepsPress() {
        val block = reason(hasConnectedRecipient = false, isTransmitting = true)
        assertEquals(PttBlockReason.NoLink, block)
        assertTrue(pttEnabledForReason(block))
    }

    private fun reason(
        voxEnabled: Boolean = false,
        appForeground: Boolean = true,
        micPermissionGranted: Boolean = true,
        audioRouteReady: Boolean = true,
        audioStartAllowed: Boolean = true,
        audioRoutePending: Boolean = false,
        showAudioRetry: Boolean = false,
        hasActiveRecipient: Boolean = true,
        hasConnectedRecipient: Boolean = true,
        isTransmitting: Boolean = false,
    ) = resolvePttBlockReason(
        voxEnabled = voxEnabled,
        appForeground = appForeground,
        micPermissionGranted = micPermissionGranted,
        audioRouteReady = audioRouteReady,
        audioStartAllowed = audioStartAllowed,
        audioRoutePending = audioRoutePending,
        showAudioRetry = showAudioRetry,
        hasActiveRecipient = hasActiveRecipient,
        hasConnectedRecipient = hasConnectedRecipient,
        isTransmitting = isTransmitting,
    )
}
