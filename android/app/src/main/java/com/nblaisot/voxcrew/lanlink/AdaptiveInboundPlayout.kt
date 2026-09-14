package com.nblaisot.voxcrew.lanlink

import android.os.Process
import android.util.Log
import com.nblaisot.voxcrew.diagnostics.AudioDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.sqrt

interface InboundFrameDecoder {
    fun decode(payload: ByteArray): ByteArray?
    fun decodeLost(): ByteArray?
}

enum class PlayoutQuantumKind { AUDIO, CONCEALMENT, SILENCE }

data class PlayoutQuantum(val pcm: ByteArray, val kind: PlayoutQuantumKind)

/** Snapshot of the real PCM sink used to pace playout. */
data class AudioSinkState(
    val bufferedPcmMs: Int = 0,
    val requiresPriming: Boolean = false,
    val startupThresholdMs: Int = 0,
    val actualBufferMs: Int = 0,
    val underruns: Int = 0,
)

/**
 * Sink-driven inbound playout.
 *
 * Network frames are queued per peer and decoded into 10 ms PCM quanta. A dedicated
 * worker keeps a small amount of audio ahead of AudioTrack; AudioTrack's playback
 * head, rather than a coroutine timer, is the playout clock. When a live source
 * temporarily runs dry, a short overlap/add continuation is emitted without
 * consuming the delayed Opus packet, so late speech is still played exactly once.
 */
class AdaptiveInboundPlayout(
    private val decoderFactory: () -> InboundFrameDecoder,
    private val writeDecodedPcm: (PlayoutQuantum) -> Boolean,
    private val audioSinkState: () -> AudioSinkState = { AudioSinkState() },
    private val nowNs: () -> Long = System::nanoTime,
    private val startWorker: Boolean = true,
    private val tag: String = TAG,
) {
    private val lock = Object()
    private val sources = linkedMapOf<String, Source>()

    @Volatile private var running = false
    private var worker: Thread? = null
    private var workerGeneration = 0L
    private var baseDelayMs = JitterBufferSettings.DEFAULT_BASE_DELAY_MS
    private var maxAdaptiveDelayMs = JitterBufferSettings.DEFAULT_MAX_ADAPTIVE_DELAY_MS
    private var adaptiveEnabled = JitterBufferSettings.DEFAULT_ADAPTIVE_ENABLED
    private var writtenQuanta = 0L
    private var pcmExpansions = 0L
    private var permanentLossConcealments = 0L
    private var silentKeepaliveQuanta = 0L
    private var activeSpeechUnderruns = 0L
    private var concealmentUnderruns = 0L
    private var intentionalIdleUnderruns = 0L
    private var previousUnderrunCount = 0
    private var lastOutputKind = PlayoutQuantumKind.SILENCE
    private var longestInboundGapMs = 0L
    private var maximumArrivalBurstFrames = 0
    @Volatile private var cachedSinkBufferedMs = 0
    @Volatile private var cachedAudioTrackUnderruns = 0
    @Volatile private var cachedActualTrackBufferMs = 0
    @Volatile private var cachedStartupThresholdMs = 0
    @Volatile private var cachedSinkRequiresPriming = false

    private val _stats = MutableStateFlow(PlayoutStats())
    val stats: StateFlow<PlayoutStats> = _stats.asStateFlow()

    fun setBaseDelayMs(ms: Int) = synchronized(lock) {
        baseDelayMs = JitterBufferSettings.coerceBaseDelayMs(ms)
        maxAdaptiveDelayMs = JitterBufferSettings.coerceMaxAdaptiveDelayMs(maxAdaptiveDelayMs, baseDelayMs)
        sources.values.forEach { source ->
            source.targetDelayMs = source.targetDelayMs.coerceIn(baseDelayMs, maxAdaptiveDelayMs)
        }
        publishStatsLocked()
        lock.notifyAll()
    }

    fun setMaxAdaptiveDelayMs(ms: Int) = synchronized(lock) {
        maxAdaptiveDelayMs = JitterBufferSettings.coerceMaxAdaptiveDelayMs(ms, baseDelayMs)
        sources.values.forEach { source ->
            source.targetDelayMs = source.targetDelayMs.coerceIn(baseDelayMs, maxAdaptiveDelayMs)
        }
        publishStatsLocked()
        lock.notifyAll()
    }

    fun setAdaptiveEnabled(enabled: Boolean) = synchronized(lock) {
        adaptiveEnabled = enabled
        if (!enabled) sources.values.forEach { it.targetDelayMs = baseDelayMs }
        publishStatsLocked()
        lock.notifyAll()
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            workerGeneration++
            val generation = workerGeneration
            if (!startWorker) return
            worker = Thread({ workerLoop(generation) }, "VoxCrewInboundPlayout").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun onMediaActivity(peerUid: String, sequence: Long, active: Boolean, receivedAtNs: Long) {
        synchronized(lock) {
            val source = sources.getOrPut(peerUid) { newSource() }
            if (active) {
                if (!source.active && source.isDrained()) {
                    source.resetTalkspurt(source.delayForNextTalkspurt(baseDelayMs, receivedAtNs))
                }
                source.active = true
                source.inputEnded = false
                source.lastArrivalNs = 0L
                source.lastAudioSequence = Long.MIN_VALUE
            } else {
                source.active = false
                source.inputEnded = true
                source.endFadePending = source.started || !source.isDrained()
                source.endedAtNs = receivedAtNs
                logTalkspurtSummaryLocked(peerUid, source)
            }
            source.lastActivitySequence = sequence
            publishStatsLocked()
            lock.notifyAll()
        }
    }

    fun enqueue(peerUid: String, sequence: Long, opusPayload: ByteArray, receivedAtNs: Long) {
        synchronized(lock) {
            val source = sources.getOrPut(peerUid) { newSource() }
            if (source.inputEnded && source.isDrained()) {
                source.resetTalkspurt(source.delayForNextTalkspurt(baseDelayMs, receivedAtNs))
            }
            source.active = true
            source.inputEnded = false
            source.recordArrival(sequence, receivedAtNs, baseDelayMs, maxAdaptiveDelayMs, adaptiveEnabled)
            longestInboundGapMs = maxOf(longestInboundGapMs, source.longestGapMs)
            maximumArrivalBurstFrames = maxOf(maximumArrivalBurstFrames, source.maximumBurstFrames)
            if (source.frames.size >= MAX_BUFFERED_AUDIO_FRAMES) {
                source.frames.removeFirst()
                source.droppedFrames++
            }
            source.frames.addLast(QueuedFrame.Audio(sequence, opusPayload.copyOf(), receivedAtNs))
            val oldestAgeMs = source.frames.firstNotNullOfOrNull {
                (it as? QueuedFrame.Audio)?.receivedAtNs
            }?.let { ((receivedAtNs - it) / 1_000_000L).coerceAtLeast(0L) } ?: 0L
            if (oldestAgeMs >= BACKLOG_WARNING_MS &&
                (source.lastBacklogWarningNs == Long.MIN_VALUE ||
                    receivedAtNs - source.lastBacklogWarningNs >= BACKLOG_WARNING_INTERVAL_NS)
            ) {
                source.lastBacklogWarningNs = receivedAtNs
                logW(
                    "inbound backlog peer=${AudioDiagnostics.peerToken(peerUid)} " +
                        "oldest=${oldestAgeMs}ms ${diagnosticSummaryLocked()}",
                )
            }
            publishStatsLocked()
            lock.notifyAll()
        }
    }

    /** Permanently unavailable media, as opposed to a merely late packet. */
    fun onPermanentLoss(peerUid: String, missingFrameCount: Int = 1) {
        synchronized(lock) {
            val source = sources.getOrPut(peerUid) { newSource() }
            val boundedCount = missingFrameCount.coerceIn(1, MAX_BUFFERED_AUDIO_FRAMES)
            repeat(boundedCount.coerceAtMost(MAX_PERMANENT_LOSS_CONCEALMENT_FRAMES)) {
                source.frames.addLast(QueuedFrame.PermanentLoss)
            }
            permanentLossConcealments += boundedCount
            bumpTargetLocked(source)
            publishStatsLocked()
            lock.notifyAll()
        }
    }

    fun reset() = synchronized(lock) {
        sources.clear()
        publishStatsLocked()
        lock.notifyAll()
    }

    fun stop() {
        val thread = synchronized(lock) {
            running = false
            workerGeneration++
            lock.notifyAll()
            worker.also { worker = null }
        }
        thread?.interrupt()
        runCatching { thread?.join(WORKER_JOIN_TIMEOUT_MS) }
        synchronized(lock) {
            sources.clear()
            publishStatsLocked()
        }
    }

    /** Deterministic entry point for JVM tests; production uses [workerLoop]. */
    internal fun processOneQuantumForTest(keepAlive: Boolean = false): Boolean {
        val quantum = synchronized(lock) { planQuantumLocked(sinkBufferedMs = 0, keepAlive) } ?: return false
        return writeQuantum(quantum)
    }

    /** Deterministic worker step for a sink whose playback head may not have started yet. */
    internal fun processOneSinkQuantumForTest(state: AudioSinkState): Boolean {
        val quantum = synchronized(lock) { planSinkQuantumLocked(state) } ?: return false
        return writeQuantum(quantum)
    }

    private fun workerLoop(generation: Long) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        while (isCurrentWorker(generation)) {
            val sink = audioSinkState().sanitized()
            val quantum = synchronized(lock) {
                if (!isCurrentWorkerLocked(generation)) return
                removeExpiredIdleSourcesLocked()
                cacheSinkStateLocked(sink)
                planSinkQuantumLocked(sink)
            } ?: continue

            if (!writeQuantum(quantum)) {
                logW("AudioTrack rejected PCM quantum")
                synchronized(lock) { waitLocked(WORKER_RETRY_MS) }
            }
        }
    }

    private fun isCurrentWorker(generation: Long): Boolean = synchronized(lock) {
        isCurrentWorkerLocked(generation)
    }

    private fun isCurrentWorkerLocked(generation: Long): Boolean =
        running && workerGeneration == generation

    private fun writeQuantum(quantum: PlayoutQuantum): Boolean {
        val written = writeDecodedPcm(quantum)
        synchronized(lock) {
            if (written) recordWrittenQuantumLocked(quantum.kind)
            publishStatsLocked()
        }
        return written
    }

    private fun planSinkQuantumLocked(sink: AudioSinkState): PlayoutQuantum? {
        val primingInProgress = sink.requiresPriming && sink.bufferedPcmMs > 0
        if (!hasReadyOrPlayingSourceLocked() && !primingInProgress) {
            waitLocked(WORKER_POLL_MS.toLong())
            return null
        }
        val fillTargetMs = sinkFillTargetMs(sink)
        if (sink.bufferedPcmMs >= fillTargetMs) {
            waitLocked(waitDurationMs(sink.bufferedPcmMs, fillTargetMs))
            return null
        }
        return planQuantumLocked(
            sinkBufferedMs = sink.bufferedPcmMs,
            keepAlive = primingInProgress || sources.values.any { it.active || it.started },
        )
    }

    private fun planQuantumLocked(sinkBufferedMs: Int, keepAlive: Boolean): PlayoutQuantum? {
        val contributions = ArrayList<ByteArray>(sources.size)
        var hasRealAudio = false
        var hasConcealment = false

        sources.values.forEach { source ->
            source.ensureDecodedQuantum()
            if (!source.started) {
                val sourceReserveNeededMs = (source.targetDelayMs - sinkBufferedMs)
                    .coerceAtLeast(QUANTUM_MS)
                if (source.queuedAudioMs() >= sourceReserveNeededMs ||
                    (source.inputEnded && source.queuedAudioMs() > 0)
                ) {
                    source.started = true
                } else {
                    return@forEach
                }
            }

            val actual = source.decoded.removeFirstOrNull()
                ?: source.ensureDecodedQuantum().let { source.decoded.removeFirstOrNull() }
            if (actual != null) {
                contributions += source.smoother.acceptActual(actual)
                source.expansionMs = 0
                source.gapSilenced = false
                hasRealAudio = true
            } else if (source.active && !source.gapSilenced && sinkBufferedMs <= LOW_WATER_MS) {
                // A late frame is not a lost frame. Fade once, then clock silence until it
                // arrives; never fabricate/repeat speech merely to hide transport latency.
                contributions += source.smoother.fadeOut()
                source.gapSilenced = true
                hasConcealment = true
                bumpTargetLocked(source)
            } else if (source.active) {
                contributions += source.smoother.silence()
            } else if (source.endFadePending && source.isDrained()) {
                contributions += source.smoother.fadeOut()
                source.endFadePending = false
                source.started = false
                hasConcealment = true
            }

            if (source.inputEnded && source.isDrained() && !source.endFadePending && !hasRealAudio) {
                source.started = false
            }
        }

        removeExpiredIdleSourcesLocked()
        if (contributions.isEmpty()) {
            return if (keepAlive) PlayoutQuantum(ByteArray(QUANTUM_BYTES), PlayoutQuantumKind.SILENCE) else null
        }
        val kind = when {
            hasRealAudio -> PlayoutQuantumKind.AUDIO
            hasConcealment -> PlayoutQuantumKind.CONCEALMENT
            else -> PlayoutQuantumKind.SILENCE
        }
        return PlayoutQuantum(mixPcm(contributions), kind)
    }

    private fun activeTargetDelayLocked(): Int = sources.values
        .filter { it.active || it.started || !it.isDrained() }
        .maxOfOrNull { it.targetDelayMs }
        ?: baseDelayMs

    private fun hasReadyOrPlayingSourceLocked(): Boolean = sources.values.any { source ->
        source.started || source.queuedAudioMs() >= source.targetDelayMs ||
            (source.inputEnded && source.queuedAudioMs() > 0)
    }

    private fun sinkFillTargetMs(sink: AudioSinkState): Int {
        val target = if (sink.requiresPriming) {
            maxOf(HARDWARE_SINK_TARGET_MS, sink.startupThresholdMs)
        } else {
            HARDWARE_SINK_TARGET_MS
        }
        return ((target + QUANTUM_MS - 1) / QUANTUM_MS * QUANTUM_MS).coerceAtLeast(QUANTUM_MS)
    }

    private fun waitDurationMs(sinkMs: Int, targetMs: Int): Long = when {
        sinkMs <= LOW_WATER_MS -> WORKER_RETRY_MS
        sinkMs >= targetMs -> WORKER_POLL_MS.toLong()
        else -> (sinkMs - LOW_WATER_MS).coerceIn(1, WORKER_POLL_MS).toLong()
    }

    private fun waitLocked(ms: Long) {
        if (!running) return
        try {
            lock.wait(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun bumpTargetLocked(source: Source) {
        if (!adaptiveEnabled) return
        source.targetDelayMs = (source.targetDelayMs + QUANTUM_MS).coerceAtMost(maxAdaptiveDelayMs)
        source.lastExpansionNs = nowNs()
    }

    private fun recordWrittenQuantumLocked(kind: PlayoutQuantumKind) {
        writtenQuanta++
        lastOutputKind = kind
        if (kind == PlayoutQuantumKind.SILENCE) silentKeepaliveQuanta++
    }

    private fun recordUnderrunsLocked(current: Int) {
        if (current < previousUnderrunCount) previousUnderrunCount = current
        val delta = (current - previousUnderrunCount).coerceAtLeast(0)
        if (delta > 0) {
            when {
                lastOutputKind == PlayoutQuantumKind.AUDIO -> activeSpeechUnderruns += delta
                sources.values.any { it.active } -> concealmentUnderruns += delta
                else -> intentionalIdleUnderruns += delta
            }
            if (sources.values.any { it.active }) {
                logW("AudioTrack underrun delta=$delta kind=$lastOutputKind ${diagnosticSummaryLocked()}")
            }
        }
        previousUnderrunCount = current
        cachedAudioTrackUnderruns = current
    }

    private fun cacheSinkStateLocked(sink: AudioSinkState) {
        cachedSinkBufferedMs = sink.bufferedPcmMs
        cachedActualTrackBufferMs = sink.actualBufferMs
        cachedStartupThresholdMs = sink.startupThresholdMs
        cachedSinkRequiresPriming = sink.requiresPriming
        recordUnderrunsLocked(sink.underruns)
    }

    private fun removeExpiredIdleSourcesLocked() {
        val now = nowNs()
        val iterator = sources.iterator()
        while (iterator.hasNext()) {
            val source = iterator.next().value
            if (source.inputEnded && source.isDrained()) {
                if (adaptiveEnabled && now - source.lastExpansionNs >= STABLE_DECAY_NS) {
                    source.targetDelayMs = baseDelayMs
                }
                if (!source.endFadePending && source.endedAtNs > 0L &&
                    now - source.endedAtNs >= SOURCE_RETENTION_NS
                ) {
                    iterator.remove()
                }
            }
        }
    }

    private fun newSource(): Source = Source(
        decoder = decoderFactory(),
        targetDelayMs = baseDelayMs,
    )

    private fun publishStatsLocked() {
        val encodedDepth = sources.values.sumOf { it.frames.size }
        val decodedDepth = sources.values.sumOf { it.decoded.size }
        val oldest = sources.values.mapNotNull { source ->
            source.frames.firstNotNullOfOrNull { (it as? QueuedFrame.Audio)?.receivedAtNs }
        }.minOrNull()
        val sinkMs = cachedSinkBufferedMs
        _stats.value = PlayoutStats(
            encodedDepth = encodedDepth,
            decodedDepth = decodedDepth,
            totalBufferedMs = encodedDepth * AudioCapture.FRAME_MS + decodedDepth * QUANTUM_MS + sinkMs,
            baseDelayMs = baseDelayMs,
            targetDelayMs = activeTargetDelayLocked(),
            oldestBacklogAgeMs = oldest?.let { ((nowNs() - it) / 1_000_000L).coerceAtLeast(0L) } ?: 0L,
            audioTrackUnderruns = cachedAudioTrackUnderruns,
            activeSpeechUnderruns = activeSpeechUnderruns,
            concealmentUnderruns = concealmentUnderruns,
            intentionalIdleUnderruns = intentionalIdleUnderruns,
            pcmExpansions = pcmExpansions,
            silentKeepaliveQuanta = silentKeepaliveQuanta,
            longestInboundGapMs = longestInboundGapMs,
            maximumArrivalBurstFrames = maximumArrivalBurstFrames,
            permanentLossConcealments = permanentLossConcealments,
            droppedFrames = sources.values.sumOf { it.droppedFrames },
            writtenQuanta = writtenQuanta,
            actualTrackBufferMs = cachedActualTrackBufferMs,
            startupThresholdMs = cachedStartupThresholdMs,
            sinkRequiresPriming = cachedSinkRequiresPriming,
        )
    }

    private fun logW(message: String) {
        runCatching { Log.w(tag, message) }
        AudioDiagnostics.event("playout", "warning", "tag" to tag, "message" to message)
    }

    private fun logTalkspurtSummaryLocked(peerUid: String, source: Source) {
        runCatching {
            Log.i(
                tag,
                "talkspurt peer=$peerUid target=${source.targetDelayMs}ms " +
                    "jitter=${"%.1f".format(source.jitterMs)}ms longestGap=${source.longestGapMs}ms " +
                    "maxBurst=${source.maximumBurstFrames} ${diagnosticSummaryLocked()}",
            )
        }
        AudioDiagnostics.event(
            "playout", "talkspurt",
            "peer" to AudioDiagnostics.peerToken(peerUid),
            "targetMs" to source.targetDelayMs,
            "jitterMs" to source.jitterMs.toInt(),
            "longestGapMs" to source.longestGapMs,
            "maxBurst" to source.maximumBurstFrames,
            "activeUnderruns" to activeSpeechUnderruns,
            "concealmentUnderruns" to concealmentUnderruns,
            "idleUnderruns" to intentionalIdleUnderruns,
        )
    }

    private fun diagnosticSummaryLocked(): String =
        "encoded=${sources.values.sumOf { it.frames.size }} decoded=${sources.values.sumOf { it.decoded.size }} " +
            "oldest=${_stats.value.oldestBacklogAgeMs}ms activeUnderruns=$activeSpeechUnderruns " +
            "concealmentUnderruns=$concealmentUnderruns idleUnderruns=$intentionalIdleUnderruns"

    private sealed interface QueuedFrame {
        data class Audio(
            val sequence: Long,
            val payload: ByteArray,
            val receivedAtNs: Long,
        ) : QueuedFrame

        data object PermanentLoss : QueuedFrame
    }

    private class Source(
        val decoder: InboundFrameDecoder,
        var targetDelayMs: Int,
    ) {
        val frames = ArrayDeque<QueuedFrame>()
        val decoded = ArrayDeque<ByteArray>()
        val smoother = PcmTailSmoother()
        var active = false
        var inputEnded = false
        var started = false
        var expansionMs = 0
        var gapSilenced = false
        var endFadePending = false
        var lastArrivalNs = 0L
        var lastAudioSequence = Long.MIN_VALUE
        var lastActivitySequence = Long.MIN_VALUE
        var jitterMs = 0.0
        var lastExpansionNs = Long.MIN_VALUE
        var endedAtNs = 0L
        var droppedFrames = 0L
        var longestGapMs = 0L
        var currentBurstFrames = 1
        var maximumBurstFrames = 1
        var lastBacklogWarningNs = Long.MIN_VALUE

        fun recordArrival(
            sequence: Long,
            receivedAtNs: Long,
            baseDelayMs: Int,
            maxDelayMs: Int,
            adaptive: Boolean,
        ) {
            if (lastArrivalNs > 0L && lastAudioSequence != Long.MIN_VALUE && sequence == lastAudioSequence + 1L) {
                val arrivalDeltaMs = (receivedAtNs - lastArrivalNs) / 1_000_000.0
                longestGapMs = maxOf(longestGapMs, arrivalDeltaMs.toLong().coerceAtLeast(0L))
                currentBurstFrames = if (arrivalDeltaMs <= BURST_ARRIVAL_MS) currentBurstFrames + 1 else 1
                maximumBurstFrames = maxOf(maximumBurstFrames, currentBurstFrames)
                val deviation = kotlin.math.abs(arrivalDeltaMs - AudioCapture.FRAME_MS)
                jitterMs += (deviation - jitterMs) / RFC_JITTER_SMOOTHING
                if (adaptive) {
                    // EWMA is deliberately stable, but a single large stall must affect
                    // the very next reserve target rather than several packets later.
                    val estimated = maxOf(
                        baseDelayMs + (2.0 * jitterMs),
                        arrivalDeltaMs + QUANTUM_MS,
                    )
                    val candidate = (ceil(estimated / QUANTUM_MS) * QUANTUM_MS)
                        .toInt()
                        .coerceIn(baseDelayMs, maxDelayMs)
                    if (candidate > targetDelayMs) {
                        targetDelayMs = candidate
                        lastExpansionNs = receivedAtNs
                    }
                }
            }
            lastArrivalNs = receivedAtNs
            lastAudioSequence = sequence
        }

        fun ensureDecodedQuantum() {
            if (decoded.isNotEmpty()) return
            val frame = frames.removeFirstOrNull() ?: return
            val pcm = when (frame) {
                is QueuedFrame.Audio -> decoder.decode(frame.payload)
                QueuedFrame.PermanentLoss -> decoder.decodeLost()
            } ?: return
            var offset = 0
            while (offset < pcm.size) {
                val end = (offset + QUANTUM_BYTES).coerceAtMost(pcm.size)
                if (end - offset == QUANTUM_BYTES) decoded.addLast(pcm.copyOfRange(offset, end))
                offset = end
            }
        }

        fun queuedAudioMs(): Int = frames.size * AudioCapture.FRAME_MS + decoded.size * QUANTUM_MS

        fun isDrained(): Boolean = frames.isEmpty() && decoded.isEmpty()

        fun delayForNextTalkspurt(baseDelayMs: Int, nowNs: Long): Int =
            if (lastExpansionNs == Long.MIN_VALUE || nowNs - lastExpansionNs >= STABLE_DECAY_NS) {
                baseDelayMs
            } else {
                targetDelayMs
            }

        fun resetTalkspurt(nextDelayMs: Int) {
            active = false
            inputEnded = false
            started = false
            expansionMs = 0
            gapSilenced = false
            endFadePending = false
            lastArrivalNs = 0L
            lastAudioSequence = Long.MIN_VALUE
            lastActivitySequence = Long.MIN_VALUE
            jitterMs = 0.0
            targetDelayMs = nextDelayMs
            endedAtNs = 0L
            longestGapMs = 0L
            currentBurstFrames = 1
            maximumBurstFrames = 1
            smoother.reset()
        }
    }

    companion object {
        private const val TAG = "AdaptiveInboundPlayout"
        const val QUANTUM_MS = 10
        const val QUANTUM_SAMPLES = AudioCapture.SAMPLE_RATE / 1000 * QUANTUM_MS
        const val QUANTUM_BYTES = QUANTUM_SAMPLES * 2
        private const val LOW_WATER_MS = 10
        const val HARDWARE_SINK_TARGET_MS = 40
        private const val WORKER_POLL_MS = 5
        private const val WORKER_RETRY_MS = 2L
        private const val WORKER_JOIN_TIMEOUT_MS = 250L
        private const val RFC_JITTER_SMOOTHING = 16.0
        private const val BURST_ARRIVAL_MS = 5.0
        private const val BACKLOG_WARNING_MS = 500L
        private const val BACKLOG_WARNING_INTERVAL_NS = 1_000_000_000L
        private const val MAX_BUFFERED_AUDIO_FRAMES = 1_500
        private const val MAX_PERMANENT_LOSS_CONCEALMENT_FRAMES = 3
        private const val STABLE_DECAY_NS = 10_000_000_000L
        private const val SOURCE_RETENTION_NS = STABLE_DECAY_NS

        internal fun mixPcm(inputs: List<ByteArray>): ByteArray {
            if (inputs.isEmpty()) return ByteArray(QUANTUM_BYTES)
            if (inputs.size == 1) return inputs.first().copyOf()
            val output = ByteArray(QUANTUM_BYTES)
            for (sampleIndex in 0 until QUANTUM_SAMPLES) {
                var mixed = 0
                inputs.forEach { pcm ->
                    val offset = sampleIndex * 2
                    mixed += ((pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xFF)).toShort().toInt()
                }
                val clipped = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output[sampleIndex * 2] = (clipped and 0xFF).toByte()
                output[sampleIndex * 2 + 1] = ((clipped ushr 8) and 0xFF).toByte()
            }
            return output
        }
    }
}

private fun AudioSinkState.sanitized(): AudioSinkState = copy(
    bufferedPcmMs = bufferedPcmMs.coerceAtLeast(0),
    startupThresholdMs = startupThresholdMs.coerceAtLeast(0),
    actualBufferMs = actualBufferMs.coerceAtLeast(0),
    underruns = underruns.coerceAtLeast(0),
)

/** Short, bounded overlap/add concealment. It never changes Opus decoder state. */
internal class PcmTailSmoother {
    private val history = ArrayDeque<Short>()
    private var lastOutput = ShortArray(AdaptiveInboundPlayout.QUANTUM_SAMPLES)
    private var expansionCount = 0

    fun acceptActual(pcm: ByteArray): ByteArray {
        val actual = pcmToShorts(pcm)
        if (expansionCount > 0) {
            val overlap = OVERLAP_SAMPLES.coerceAtMost(actual.size)
            for (i in 0 until overlap) {
                val incomingWeight = (i + 1).toDouble() / overlap
                val concealed = lastOutput[lastOutput.size - overlap + i]
                actual[i] = (concealed * (1.0 - incomingWeight) + actual[i] * incomingWeight).toInt().toShort()
            }
        }
        expansionCount = 0
        appendHistory(actual)
        lastOutput = actual.copyOf()
        return shortsToPcm(actual)
    }

    fun expand(): ByteArray {
        if (history.isEmpty()) return ByteArray(AdaptiveInboundPlayout.QUANTUM_BYTES)
        val samples = history.toShortArray()
        val lag = bestLag(samples)
        val output = ShortArray(AdaptiveInboundPlayout.QUANTUM_SAMPLES)
        val gain = expansionGain(expansionCount)
        for (i in output.indices) {
            val sourceIndex = (samples.size - lag + (i % lag)).coerceIn(0, samples.lastIndex)
            var value = samples[sourceIndex].toDouble()
            if (i < OVERLAP_SAMPLES) {
                val generatedWeight = (i + 1).toDouble() / OVERLAP_SAMPLES
                value = lastOutput[lastOutput.size - OVERLAP_SAMPLES + i] * (1.0 - generatedWeight) +
                    value * generatedWeight
            }
            output[i] = (value * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        expansionCount++
        appendHistory(output)
        lastOutput = output.copyOf()
        return shortsToPcm(output)
    }

    /** Marks a fully silent quantum while retaining the resume-crossfade state. */
    fun silence(): ByteArray {
        lastOutput.fill(0)
        expansionCount = maxOf(expansionCount, 1)
        return ByteArray(AdaptiveInboundPlayout.QUANTUM_BYTES)
    }

    /** One 10 ms ramp from the most recent output to zero at a clean talkspurt end. */
    fun fadeOut(): ByteArray {
        val output = ShortArray(lastOutput.size)
        for (i in output.indices) {
            val gain = 1.0 - (i + 1).toDouble() / output.size
            output[i] = (lastOutput[i] * gain).toInt().toShort()
        }
        lastOutput.fill(0)
        expansionCount = maxOf(expansionCount, 1)
        return shortsToPcm(output)
    }

    fun reset() {
        history.clear()
        lastOutput.fill(0)
        expansionCount = 0
    }

    private fun appendHistory(samples: ShortArray) {
        samples.forEach(history::addLast)
        while (history.size > HISTORY_SAMPLES) history.removeFirst()
    }

    private fun bestLag(samples: ShortArray): Int {
        if (samples.size < AdaptiveInboundPlayout.QUANTUM_SAMPLES + MAX_LAG) return DEFAULT_LAG
        var bestLag = DEFAULT_LAG
        var bestScore = Double.NEGATIVE_INFINITY
        val referenceStart = samples.size - CORRELATION_SAMPLES
        for (lag in MIN_LAG..MAX_LAG step 2) {
            val candidateStart = referenceStart - lag
            if (candidateStart < 0) continue
            var dot = 0.0
            var refEnergy = 1.0
            var candidateEnergy = 1.0
            for (i in 0 until CORRELATION_SAMPLES) {
                val a = samples[referenceStart + i].toDouble()
                val b = samples[candidateStart + i].toDouble()
                dot += a * b
                refEnergy += a * a
                candidateEnergy += b * b
            }
            val score = dot / sqrt(refEnergy * candidateEnergy)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return bestLag
    }

    private fun expansionGain(count: Int): Double = when {
        count == 0 -> 1.0
        count >= MAX_EXPANSION_QUANTA - 1 -> 0.0
        else -> 1.0 - count.toDouble() / (MAX_EXPANSION_QUANTA - 1)
    }

    private fun pcmToShorts(bytes: ByteArray): ShortArray {
        val output = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(output)
        return output
    }

    private fun shortsToPcm(samples: ShortArray): ByteArray {
        val output = ByteArray(samples.size * 2)
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        return output
    }

    companion object {
        private const val HISTORY_SAMPLES = AudioCapture.SAMPLE_RATE * 30 / 1_000
        private const val OVERLAP_SAMPLES = AdaptiveInboundPlayout.QUANTUM_SAMPLES
        private const val CORRELATION_SAMPLES = OVERLAP_SAMPLES
        private const val MIN_LAG = AudioCapture.SAMPLE_RATE * 25 / 10_000
        private const val MAX_LAG = AudioCapture.SAMPLE_RATE * 125 / 10_000
        private const val DEFAULT_LAG = AudioCapture.SAMPLE_RATE * 10 / 1_000
        private const val MAX_EXPANSION_QUANTA = 6
    }
}
