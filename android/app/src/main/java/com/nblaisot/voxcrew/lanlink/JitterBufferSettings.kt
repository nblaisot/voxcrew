package com.nblaisot.voxcrew.lanlink

/** User-tunable inbound jitter buffer parameters. */
object JitterBufferSettings {
    const val MIN_BASE_DELAY_MS = 20
    const val MAX_BASE_DELAY_MS = 80
    const val DEFAULT_BASE_DELAY_MS = 40

    const val MIN_MAX_ADAPTIVE_DELAY_MS = 40
    const val MAX_MAX_ADAPTIVE_DELAY_MS = 160
    const val DEFAULT_MAX_ADAPTIVE_DELAY_MS = 80

    const val DEFAULT_ADAPTIVE_ENABLED = true

    fun coerceBaseDelayMs(ms: Int): Int =
        ms.coerceIn(MIN_BASE_DELAY_MS, MAX_BASE_DELAY_MS)
            .let { aligned -> aligned - (aligned % AudioCapture.FRAME_MS) }
            .coerceAtLeast(MIN_BASE_DELAY_MS)

    fun coerceMaxAdaptiveDelayMs(ms: Int, baseDelayMs: Int): Int {
        val floor = baseDelayMs.coerceAtLeast(MIN_MAX_ADAPTIVE_DELAY_MS)
        return ms.coerceIn(floor, MAX_MAX_ADAPTIVE_DELAY_MS)
            .let { aligned -> aligned - (aligned % AudioCapture.FRAME_MS) }
            .coerceAtLeast(floor)
    }

    fun targetDelayFrames(delayMs: Int): Int =
        (delayMs + AudioCapture.FRAME_MS - 1) / AudioCapture.FRAME_MS
}

data class PlayoutStats(
    val encodedDepth: Int = 0,
    val decodedDepth: Int = 0,
    val totalBufferedMs: Int = 0,
    val baseDelayMs: Int = JitterBufferSettings.DEFAULT_BASE_DELAY_MS,
    val targetDelayMs: Int = JitterBufferSettings.DEFAULT_BASE_DELAY_MS,
    val oldestBacklogAgeMs: Long = 0,
    val audioTrackUnderruns: Int = 0,
    val pcmExpansions: Long = 0,
    val permanentLossConcealments: Long = 0,
    val droppedFrames: Long = 0,
    val writtenQuanta: Long = 0,
    val actualTrackBufferMs: Int = 0,
)
