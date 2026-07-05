package com.nblaisot.voxcrew.audio

/**
 * Turns raw, per-frame [VoiceDetector] decisions into a stable transmit/hold state for
 * VOX. Sits on top of (not instead of) Silero's own frame-continuity smoothing
 * ([SileroVoiceDetector]'s `speechDurationMs`/`silenceDurationMs`): this class owns the
 * app-level hangover that bridges natural pauses between words so a talkspurt isn't
 * chopped up sentence by sentence, plus the onset edge the capture pipeline uses to
 * know exactly when to flush its pre-roll buffer.
 *
 * Pure and clock-injected (via [update]'s `nowMs`) so it can be unit-tested without any
 * real audio or coroutines.
 */
class VoxGate(private val hangoverMs: Long = DEFAULT_HANGOVER_MS) {
    private var lastSpeechAtMs: Long? = null
    private var transmitting = false

    /**
     * Call once per audio frame with the latest speech decision. Pass `null` when the
     * detector hasn't produced a new decision yet for this frame (still accumulating
     * samples) — the previous decision's hangover keeps counting down against [nowMs].
     */
    fun update(speech: Boolean?, nowMs: Long): VoxGateResult {
        if (speech == true) lastSpeechAtMs = nowMs
        val speechAt = lastSpeechAtMs
        val withinHangover = speechAt != null && (nowMs - speechAt) <= hangoverMs
        val wasTransmitting = transmitting
        transmitting = withinHangover
        return VoxGateResult(transmitting = transmitting, onset = transmitting && !wasTransmitting)
    }

    /** Resets to idle, e.g. when VOX is toggled off and back on. */
    fun reset() {
        lastSpeechAtMs = null
        transmitting = false
    }

    companion object {
        const val DEFAULT_HANGOVER_MS = 600L
    }
}

data class VoxGateResult(
    /** Whether the gate is currently open — the capture pipeline should be sending audio. */
    val transmitting: Boolean,
    /** True exactly on the frame transmission starts, so pre-roll is flushed once per talkspurt. */
    val onset: Boolean,
)
