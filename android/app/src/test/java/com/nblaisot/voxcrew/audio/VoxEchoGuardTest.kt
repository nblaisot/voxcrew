package com.nblaisot.voxcrew.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxEchoGuardTest {
    @Test
    fun passesSpeechThroughWhenNotReceiving() {
        val guard = VoxEchoGuard(suppressMs = 100)
        guard.onReceivingChanged(receiving = false, nowMs = 0)

        assertEquals(true, guard.filterSpeechDecision(speech = true, nowMs = 10))
    }

    @Test
    fun suppressesSpeechAtStartOfReceiving() {
        val guard = VoxEchoGuard(suppressMs = 100)
        guard.onReceivingChanged(receiving = true, nowMs = 1_000)

        assertEquals(false, guard.filterSpeechDecision(speech = true, nowMs = 1_050))
    }

    @Test
    fun allowsSpeechAfterSuppressWindow() {
        val guard = VoxEchoGuard(suppressMs = 100)
        guard.onReceivingChanged(receiving = true, nowMs = 1_000)

        assertEquals(true, guard.filterSpeechDecision(speech = true, nowMs = 1_150))
    }

    @Test
    fun resetClearsReceivingState() {
        val guard = VoxEchoGuard(suppressMs = 100)
        guard.onReceivingChanged(receiving = true, nowMs = 0)
        guard.reset()

        assertEquals(true, guard.filterSpeechDecision(speech = true, nowMs = 10))
    }

    @Test
    fun doesNotReopenSuppressWindowWhileReceivingStaysTrue() {
        val guard = VoxEchoGuard(suppressMs = 100)
        guard.onReceivingChanged(receiving = true, nowMs = 0)
        assertEquals(false, guard.filterSpeechDecision(speech = true, nowMs = 50))

        guard.onReceivingChanged(receiving = true, nowMs = 200)
        assertEquals(true, guard.filterSpeechDecision(speech = true, nowMs = 250))
    }
}
