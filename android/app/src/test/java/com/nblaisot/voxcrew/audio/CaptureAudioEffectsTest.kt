package com.nblaisot.voxcrew.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAudioEffectsTest {
    @Test
    fun attachEnablesAvailableEffects() {
        val aec = FakeAudioEffectHandle(isAvailable = true)
        val ns = FakeAudioEffectHandle(isAvailable = true)
        val agc = FakeAudioEffectHandle(isAvailable = false)

        val effects = CaptureAudioEffects.attach(
            sessionId = 42,
            factories = AudioEffectFactories(
                createAec = { aec },
                createNs = { ns },
                createAgc = { agc },
            ),
        )

        assertTrue(effects.diagnostics.aecEnabled)
        assertTrue(effects.diagnostics.nsEnabled)
        assertFalse(effects.diagnostics.agcEnabled)
        assertFalse(agc.enabled)
    }

    @Test
    fun attachHandlesUnavailableFactories() {
        val effects = CaptureAudioEffects.attach(
            sessionId = 1,
            factories = AudioEffectFactories(
                createAec = { null },
                createNs = { null },
                createAgc = { null },
            ),
        )

        assertFalse(effects.diagnostics.aecAvailable)
        assertFalse(effects.diagnostics.nsAvailable)
        assertFalse(effects.diagnostics.agcAvailable)
    }

    @Test
    fun releaseIsIdempotent() {
        val aec = FakeAudioEffectHandle(isAvailable = true)
        val effects = CaptureAudioEffects.attach(
            sessionId = 7,
            factories = AudioEffectFactories(
                createAec = { aec },
                createNs = { null },
                createAgc = { null },
            ),
        )

        effects.release()
        effects.release()

        assertEquals(1, aec.releaseCount)
    }
}

private class FakeAudioEffectHandle(
    override val isAvailable: Boolean,
) : AudioEffectHandle {
    override var enabled: Boolean = false
    var releaseCount = 0

    override fun release() {
        releaseCount++
    }
}
