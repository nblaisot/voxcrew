package com.nblaisot.voxcrew.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Deterministic, frame-based speech leveling. It has no clock, timer, or device policy. */
class PcmSpeechLeveler(
    private val targetRms: Double = TARGET_RMS,
    private val silenceGateRms: Double = SILENCE_GATE_RMS,
    private val minGain: Double = MIN_GAIN,
    private val maxGain: Double = MAX_GAIN,
    private val limiterCeiling: Int = LIMITER_CEILING,
    private val attack: Double = ATTACK,
    private val release: Double = RELEASE,
) {
    private var gain = 1.0

    fun reset() {
        gain = 1.0
    }

    fun process(pcm: ByteArray): LeveledPcm {
        require(pcm.size % 2 == 0) { "PCM16 byte count must be even" }
        val samples = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val inputRms = rms(samples)
        val desired = if (inputRms < silenceGateRms) {
            1.0
        } else {
            (targetRms / inputRms).coerceIn(minGain, maxGain)
        }
        val coefficient = if (desired > gain) attack else release
        gain += (desired - gain) * coefficient

        val output = ByteArray(pcm.size)
        val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            val leveled = (sample.toDouble() * gain).toInt()
                .coerceIn(-limiterCeiling, limiterCeiling)
            buffer.putShort(leveled.toShort())
        }
        return LeveledPcm(
            bytes = output,
            inputRms = inputRms.toInt(),
            outputRms = rmsBytes(output).toInt(),
            gain = gain,
        )
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        samples.forEach { sample ->
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / samples.size)
    }

    private fun rmsBytes(pcm: ByteArray): Double {
        val samples = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return rms(samples)
    }

    companion object {
        const val TARGET_RMS = 2_500.0
        const val SILENCE_GATE_RMS = 64.0
        const val MIN_GAIN = 0.5011872336272722 // -6 dB
        const val MAX_GAIN = 7.943282347242816 // +18 dB
        const val LIMITER_CEILING = 30_000
        const val ATTACK = 0.25
        const val RELEASE = 0.50
    }
}

data class LeveledPcm(
    val bytes: ByteArray,
    val inputRms: Int,
    val outputRms: Int,
    val gain: Double,
)
