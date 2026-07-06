package com.nblaisot.voxcrew.audio

/**
 * Suppresses VOX speech decisions for a short window after remote playback starts,
 * reducing false triggers from residual acoustic echo on devices with weak platform AEC.
 * PTT is unaffected — this guard is only applied in the VOX capture path.
 */
class VoxEchoGuard(private val suppressMs: Long = DEFAULT_SUPPRESS_MS) {
    private var receivingSinceMs: Long? = null
    private var wasReceiving = false

    fun onReceivingChanged(receiving: Boolean, nowMs: Long) {
        if (receiving && !wasReceiving) {
            receivingSinceMs = nowMs
        } else if (!receiving) {
            receivingSinceMs = null
        }
        wasReceiving = receiving
    }

    /**
     * Returns the speech decision to feed into [VoxGate], forcing `false` during the
     * suppress window after inbound audio begins.
     */
    fun filterSpeechDecision(speech: Boolean?, nowMs: Long): Boolean? {
        val since = receivingSinceMs ?: return speech
        return if (nowMs - since < suppressMs) false else speech
    }

    fun reset() {
        receivingSinceMs = null
        wasReceiving = false
    }

    companion object {
        const val DEFAULT_SUPPRESS_MS = 100L
    }
}
