package com.nblaisot.voxcrew.lanlink

import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nblaisot.voxcrew.diagnostics.AudioDiagnostics

/** Actual hardware limits after Android has adjusted the requested streaming buffer. */
internal data class AudioTrackPlayoutConfiguration(
    val capacityFrames: Int,
    val bufferSizeFrames: Int,
    val startupThresholdFrames: Int,
)

/**
 * Keeps the hardware PCM queue small while the adaptive jitter reserve remains in software.
 * On Android 12+ the start threshold is explicit; older releases use the effective buffer
 * size as a conservative threshold, matching AudioTrack's documented priming behaviour.
 */
internal fun configureAudioTrackForPlayout(
    track: AudioTrack,
    sampleRate: Int = AudioCapture.SAMPLE_RATE,
): AudioTrackPlayoutConfiguration {
    val requestedFrames = framesForMs(AdaptiveInboundPlayout.HARDWARE_SINK_TARGET_MS, sampleRate)
    val resizeResult = runCatching { track.setBufferSizeInFrames(requestedFrames) }.getOrDefault(0)
    val actualBufferFrames = if (resizeResult > 0) resizeResult else track.bufferSizeInFrames
    val capacityFrames = track.bufferCapacityInFrames
    val startupThresholdFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val requestedThreshold = requestedFrames.coerceIn(1, capacityFrames.coerceAtLeast(1))
        runCatching { track.setStartThresholdInFrames(requestedThreshold) }
            .getOrElse { track.startThresholdInFrames }
    } else {
        actualBufferFrames
    }.coerceAtLeast(1)
    return AudioTrackPlayoutConfiguration(
        capacityFrames = capacityFrames,
        bufferSizeFrames = actualBufferFrames,
        startupThresholdFrames = startupThresholdFrames,
    )
}

/** Mutable accounting for one AudioTrack generation; callers serialize access with their lock. */
internal class AudioTrackSinkTracker(
    private val tag: String,
    private val sampleRate: Int = AudioCapture.SAMPLE_RATE,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var configuration = AudioTrackPlayoutConfiguration(0, 0, 1)
    private var submittedFrames = 0L
    private var lastPlaybackHeadFrames = 0L
    private var lastUnderruns = 0
    private var requiresPriming = true
    private var primingBeganAtMs = 0L
    private var stallReported = false

    fun reset(configuration: AudioTrackPlayoutConfiguration) {
        this.configuration = configuration
        submittedFrames = 0L
        lastPlaybackHeadFrames = 0L
        lastUnderruns = 0
        requiresPriming = true
        primingBeganAtMs = 0L
        stallReported = false
    }

    fun clear() = reset(AudioTrackPlayoutConfiguration(0, 0, 1))

    fun recordSubmittedFrames(count: Int) {
        if (count <= 0) return
        if (requiresPriming && primingBeganAtMs == 0L) primingBeganAtMs = clockMs()
        submittedFrames += count
    }

    fun snapshot(track: AudioTrack): AudioSinkState {
        val playbackHeadFrames = Integer.toUnsignedLong(track.playbackHeadPosition)
        val bufferedFrames = (submittedFrames - playbackHeadFrames).coerceAtLeast(0L)
        val underruns = track.underrunCount.coerceAtLeast(0)
        val previousThresholdFrames = configuration.startupThresholdFrames
        var currentBufferFrames = track.bufferSizeInFrames.coerceAtLeast(1)
        var currentThresholdFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            track.startThresholdInFrames
        } else {
            currentBufferFrames
        }.coerceAtLeast(1)
        if (underruns > lastUnderruns) {
            requiresPriming = true
            val currentMs = msForFrames(currentBufferFrames.toLong(), sampleRate)
            val requestedMs = (currentMs + BUFFER_GROWTH_STEP_MS).coerceAtMost(MAX_DYNAMIC_BUFFER_MS)
            val requestedFrames = framesForMs(requestedMs, sampleRate)
                .coerceAtMost(track.bufferCapacityInFrames.coerceAtLeast(1))
            val resized = runCatching { track.setBufferSizeInFrames(requestedFrames) }.getOrDefault(0)
            if (resized > 0) currentBufferFrames = resized
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                currentThresholdFrames = runCatching {
                    track.setStartThresholdInFrames(currentBufferFrames)
                }.getOrElse { track.startThresholdInFrames }.coerceAtLeast(1)
            }
            AudioDiagnostics.event(
                "playout", "underrun",
                "tag" to tag, "delta" to underruns - lastUnderruns,
                "bufferMsBefore" to currentMs,
                "bufferMsAfter" to msForFrames(currentBufferFrames.toLong(), sampleRate),
                "submittedFrames" to submittedFrames,
                "playbackHeadFrames" to playbackHeadFrames,
            )
        }
        configuration = configuration.copy(
            capacityFrames = track.bufferCapacityInFrames,
            bufferSizeFrames = currentBufferFrames,
            startupThresholdFrames = currentThresholdFrames,
        )
        if (currentThresholdFrames > previousThresholdFrames && bufferedFrames < currentThresholdFrames) {
            requiresPriming = true
        }
        if (playbackHeadFrames != lastPlaybackHeadFrames && bufferedFrames > 0L) {
            requiresPriming = false
            primingBeganAtMs = 0L
            stallReported = false
        }
        if (submittedFrames > 0L && bufferedFrames == 0L) {
            if (!requiresPriming) primingBeganAtMs = 0L
            requiresPriming = true
        }

        lastPlaybackHeadFrames = playbackHeadFrames
        lastUnderruns = underruns
        maybeReportStartupStall(bufferedFrames, playbackHeadFrames)

        return AudioSinkState(
            bufferedPcmMs = msForFrames(bufferedFrames, sampleRate),
            requiresPriming = requiresPriming,
            startupThresholdMs = msForFrames(configuration.startupThresholdFrames.toLong(), sampleRate),
            actualBufferMs = msForFrames(configuration.bufferSizeFrames.toLong(), sampleRate),
            underruns = underruns,
        )
    }

    private fun maybeReportStartupStall(bufferedFrames: Long, playbackHeadFrames: Long) {
        if (!requiresPriming || primingBeganAtMs == 0L || stallReported) return
        if (bufferedFrames < configuration.startupThresholdFrames) return
        val stalledForMs = clockMs() - primingBeganAtMs
        if (stalledForMs < STARTUP_STALL_WARNING_MS) return
        stallReported = true
        Log.e(
            tag,
            "AudioTrack startup stalled for ${stalledForMs}ms submitted=$submittedFrames " +
                "head=$playbackHeadFrames buffered=$bufferedFrames " +
                "threshold=${configuration.startupThresholdFrames} " +
                "buffer=${configuration.bufferSizeFrames} capacity=${configuration.capacityFrames}",
        )
        AudioDiagnostics.event(
            "playout", "startup_stall",
            "tag" to tag, "stalledMs" to stalledForMs,
            "submittedFrames" to submittedFrames, "headFrames" to playbackHeadFrames,
            "bufferedFrames" to bufferedFrames,
            "thresholdFrames" to configuration.startupThresholdFrames,
            "capacityFrames" to configuration.capacityFrames,
        )
    }

    companion object {
        private const val STARTUP_STALL_WARNING_MS = 750L
        private const val BUFFER_GROWTH_STEP_MS = 20
        private const val MAX_DYNAMIC_BUFFER_MS = 100
    }
}

internal fun framesForMs(ms: Int, sampleRate: Int): Int =
    (sampleRate.toLong() * ms / 1_000L).toInt().coerceAtLeast(1)

private fun msForFrames(frames: Long, sampleRate: Int): Int =
    ((frames * 1_000L + sampleRate - 1L) / sampleRate).toInt().coerceAtLeast(0)
