package com.nblaisot.voxcrew.audio

import android.content.Context
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

/**
 * [VoiceDetector] backed by Silero VAD (https://github.com/snakers4/silero-vad), a
 * small (~2 MB) deep neural network trained to discriminate human speech from
 * background noise — run fully on-device and offline via ONNX Runtime Mobile, through
 * the https://github.com/gkonovalov/android-vad Kotlin wrapper. Chosen over an
 * RMS/energy gate or the classic WebRTC GMM VAD because both are known to
 * false-trigger on wind, traffic and other non-stationary outdoor noise; Silero is
 * markedly more robust in that setting, which matters for outdoor VOX use.
 *
 * The capture pipeline (see [com.nblaisot.voxcrew.lanlink.AudioCapture]) hands over
 * 20 ms frames (320 samples at 16 kHz), but Silero's 16 kHz model only accepts fixed
 * 512/1024/1536-sample windows. [pending] accumulates samples across calls and runs
 * inference every time a full [FRAME_SIZE] window is available, so [accept] may
 * return zero, one, or (rarely, after a scheduling hiccup) more than one decision's
 * worth of audio per call — only the most recent decision is reported back.
 */
class SileroVoiceDetector(
    context: Context,
    sensitivity: VoxSensitivity = VoxSensitivity.DEFAULT,
) : VoiceDetector {
    private val vad: VadSilero = Vad.builder()
        .setContext(context.applicationContext)
        .setSampleRate(SampleRate.SAMPLE_RATE_16K)
        .setFrameSize(FRAME_SIZE)
        .setMode(sensitivity.toMode())
        .setSpeechDurationMs(sensitivity.toSpeechDurationMs())
        .setSilenceDurationMs(SILENCE_DURATION_MS)
        .build()

    private val pending = ArrayDeque<Short>()

    override fun accept(pcm: ShortArray): Boolean? {
        pending.addAll(pcm.asIterable())
        var lastDecision: Boolean? = null
        while (pending.size >= FRAME_SIZE.value) {
            val chunk = ShortArray(FRAME_SIZE.value) { pending.removeFirst() }
            lastDecision = vad.isSpeech(chunk)
        }
        return lastDecision
    }

    override fun close() {
        runCatching { vad.close() }
    }

    companion object {
        private val FRAME_SIZE = FrameSize.FRAME_SIZE_512

        /**
         * Fixed hangover applied *inside* Silero's own frame-continuity smoothing
         * (see [VadSilero]), independent of and shorter than [VoxGate]'s own
         * app-level hangover, which is what actually keeps the mic transmitting
         * through natural pauses between words.
         */
        private const val SILENCE_DURATION_MS = 300

        private fun VoxSensitivity.toMode(): Mode = when (level) {
            1, 2 -> Mode.VERY_AGGRESSIVE
            3, 4 -> Mode.AGGRESSIVE
            else -> Mode.NORMAL
        }

        private fun VoxSensitivity.toSpeechDurationMs(): Int = when (level) {
            1 -> 200
            2, 3 -> 100
            else -> 50
        }
    }
}
