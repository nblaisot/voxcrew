package com.nblaisot.voxcrew.connectivity.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcNegotiationGuardTest {
    @Test
    fun initiatorCanStartOfferOnlyWhenIdle() {
        val guard = WebRtcNegotiationGuard(isInitiator = true)
        assertTrue(guard.canStartOffer())
        guard.onOfferStarted()
        assertFalse(guard.canStartOffer())
        guard.onOfferSent()
        assertFalse(guard.canStartOffer())
        guard.onNegotiationComplete()
        assertTrue(guard.canStartOffer())
    }

    @Test
    fun answererNeverStartsOffer() {
        val guard = WebRtcNegotiationGuard(isInitiator = false)
        assertFalse(guard.canStartOffer())
    }

    @Test
    fun glareInitiatorIgnoresIncomingOffer() {
        val guard = WebRtcNegotiationGuard(isInitiator = true)
        guard.onOfferStarted()
        assertEquals(GlareAction.IGNORE, guard.handleIncomingOffer())
    }

    @Test
    fun glareAnswererAcceptsIncomingOffer() {
        val guard = WebRtcNegotiationGuard(isInitiator = false)
        guard.onOfferStarted()
        assertEquals(GlareAction.ANSWER, guard.handleIncomingOffer())
    }
}
