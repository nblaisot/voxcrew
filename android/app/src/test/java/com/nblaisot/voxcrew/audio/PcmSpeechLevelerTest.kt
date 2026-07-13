package com.nblaisot.voxcrew.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSpeechLevelerTest {
    @Test
    fun silenceRemainsSilence() {
        val silence = pcm(ShortArray(320))
        val result = PcmSpeechLeveler().process(silence)
        assertArrayEquals(silence, result.bytes)
        assertEquals(0, result.outputRms)
        assertEquals(1.0, result.gain, 0.0)
    }

    @Test
    fun quietSpeechApproachesTargetWithinMaximumGain() {
        val leveler = PcmSpeechLeveler()
        var result = leveler.process(pcm(ShortArray(320) { 200 }))
        repeat(20) { result = leveler.process(pcm(ShortArray(320) { 200 })) }
        assertTrue(result.outputRms > 1_400)
        assertTrue(result.gain <= PcmSpeechLeveler.MAX_GAIN)
    }

    @Test
    fun loudSpeechIsAttenuatedAndLimitedWithoutOverflow() {
        val leveler = PcmSpeechLeveler()
        var result = leveler.process(pcm(ShortArray(320) { 32_000 }))
        repeat(8) { result = leveler.process(pcm(ShortArray(320) { 32_000 })) }
        val output = samples(result.bytes)
        assertTrue(result.gain >= PcmSpeechLeveler.MIN_GAIN)
        assertTrue(output.all { abs(it.toInt()) <= PcmSpeechLeveler.LIMITER_CEILING })
    }

    @Test
    fun attackAndReleaseAreDeterministicPerFrameAndResettable() {
        val quiet = pcm(ShortArray(320) { 500 })
        val loud = pcm(ShortArray(320) { 10_000 })
        val leveler = PcmSpeechLeveler()
        val attackGain = leveler.process(quiet).gain
        val releaseGain = leveler.process(loud).gain
        assertEquals(2.0, attackGain, 0.0001)
        assertEquals(1.2505936168, releaseGain, 0.0001)
        leveler.reset()
        assertEquals(attackGain, leveler.process(quiet).gain, 0.0)
    }

    private fun pcm(samples: ShortArray): ByteArray = ByteBuffer
        .allocate(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .also { buffer -> samples.forEach(buffer::putShort) }
        .array()

    private fun samples(pcm: ByteArray): ShortArray = ShortArray(pcm.size / 2).also {
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
    }
}
