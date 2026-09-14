package com.nblaisot.voxcrew.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxGateTest {
    @Test
    fun idleByDefault() {
        val gate = VoxGate(hangoverMs = 500)
        val result = gate.update(speech = null, nowMs = 0)
        assertFalse(result.transmitting)
        assertFalse(result.onset)
    }

    @Test
    fun opensOnSpeechAndReportsOnsetOnce() {
        val gate = VoxGate(hangoverMs = 500)

        val first = gate.update(speech = true, nowMs = 0)
        assertTrue(first.transmitting)
        assertTrue(first.onset)

        val second = gate.update(speech = true, nowMs = 20)
        assertTrue(second.transmitting)
        assertFalse("onset must only fire once per talkspurt", second.onset)
    }

    @Test
    fun holdsThroughInterWordPauseWithinHangover() {
        val gate = VoxGate(hangoverMs = 500)

        gate.update(speech = true, nowMs = 0)
        val duringPause = gate.update(speech = false, nowMs = 300)

        assertTrue("should still be transmitting inside the hangover window", duringPause.transmitting)
        assertFalse(duringPause.onset)
    }

    @Test
    fun closesAfterHangoverExpires() {
        val gate = VoxGate(hangoverMs = 500)

        gate.update(speech = true, nowMs = 0)
        val afterHangover = gate.update(speech = false, nowMs = 600)

        assertFalse(afterHangover.transmitting)
    }

    @Test
    fun reopensWithNewOnsetAfterClosing() {
        val gate = VoxGate(hangoverMs = 500)

        gate.update(speech = true, nowMs = 0)
        gate.update(speech = false, nowMs = 600)
        val reopened = gate.update(speech = true, nowMs = 1000)

        assertTrue(reopened.transmitting)
        assertTrue("re-triggering speech after a full close should report a fresh onset", reopened.onset)
    }

    @Test
    fun ignoresShortNoiseBurstWithoutASpeechDecision() {
        val gate = VoxGate(hangoverMs = 500)

        // No speech decision reported yet (detector still accumulating samples).
        val result = gate.update(speech = null, nowMs = 20)

        assertFalse(result.transmitting)
    }

    @Test
    fun resetClearsHangoverAndTransmittingState() {
        val gate = VoxGate(hangoverMs = 500)

        gate.update(speech = true, nowMs = 0)
        gate.reset()
        val afterReset = gate.update(speech = null, nowMs = 10)

        assertFalse(afterReset.transmitting)
    }

    @Test
    fun bluetoothHangoverBridgesElevenHundredMillisecondGap() {
        val gate = VoxGate()
        gate.setHangoverMs(1_200)
        gate.update(speech = true, nowMs = 0)

        assertTrue(gate.update(speech = false, nowMs = 1_100).transmitting)
        assertFalse(gate.update(speech = false, nowMs = 1_201).transmitting)
    }

    @Test
    fun routeChangeImmediatelyRestoresBuiltinHangover() {
        val gate = VoxGate()
        gate.setHangoverMs(1_200)
        gate.update(speech = true, nowMs = 0)
        assertTrue(gate.update(speech = false, nowMs = 700).transmitting)

        gate.setHangoverMs(600)

        assertFalse(gate.update(speech = false, nowMs = 700).transmitting)
    }
}
