package com.nblaisot.voxcrew.audio

/**
 * Analyzes raw microphone audio to decide whether the current frame contains human
 * speech, as opposed to silence or non-voice noise (wind, traffic, footsteps…). Kept
 * as an interface so the acoustic model (see [SileroVoiceDetector]) is swappable
 * without touching [VoxGate] or the capture pipeline.
 */
interface VoiceDetector {
    /**
     * Feeds one 20 ms PCM frame (16-bit mono samples at 16 kHz, see
     * [com.nblaisot.voxcrew.lanlink.AudioCapture]). Implementations may need to
     * accumulate several frames before they have enough samples to run one analysis
     * window, in which case they return `null` until a decision is available — the
     * caller should keep using the last known decision (see [VoxGate]) rather than
     * treating `null` as silence.
     *
     * @return `true`/`false` if a new decision was produced this call, `null` if more
     * samples are still needed.
     */
    fun accept(pcm: ShortArray): Boolean?

    /** Releases any native resources (ONNX session, etc). Safe to call more than once. */
    fun close()
}
