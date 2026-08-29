package com.nblaisot.voxcrew.lanlink

import android.os.Process
import android.util.Log
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
    private val writeDecodedPcm: (ByteArray) -> Boolean,
    private val bufferedPcmMs: () -> Int = { 0 },
    private val audioTrackUnderruns: () -> Int = { 0 },
    private val actualTrackBufferMs: () -> Int = { 0 },
    private val nowNs: () -> Long = System::nanoTime,
    private val startWorker: Boolean = true,
    private val tag: String = TAG,
) {
    private val lock = Object()
    private val sources = linkedMapOf<String, Source>()

    @Volatile private var running = false
    private var worker: Thread? = null
    private var baseDelayMs = JitterBufferSettings.DEFAULT_BASE_DELAY_MS
    private var maxAdaptiveDelayMs = JitterBufferSettings.DEFAULT_MAX_ADAPTIVE_DELAY_MS
    private var adaptiveEnabled = JitterBufferSettings.DEFAULT_ADAPTIVE_ENABLED
    private var writtenQuanta = 0L
    private var pcmExpansions = 0L
    private var permanentLossConcealments = 0L
    @Volatile private var cachedSinkBufferedMs = 0
    @Volatile private var cachedAudioTrackUnderruns = 0
    @Volatile private var cachedActualTrackBufferMs = 0

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
            if (!startWorker) return
            worker = Thread(::workerLoop, "VoxCrewInboundPlayout").apply {
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
                source.endedAtNs = receivedAtNs
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
            if (source.frames.size >= MAX_BUFFERED_AUDIO_FRAMES) {
                source.frames.removeFirst()
                source.droppedFrames++
            }
            source.frames.addLast(QueuedFrame.Audio(sequence, opusPayload.copyOf(), receivedAtNs))
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
            lock.notifyAll()
            worker.also { worker = null }
        }
        thread?.interrupt()
        synchronized(lock) {
            sources.clear()
            publishStatsLocked()
        }
    }

    /** Deterministic entry point for JVM tests; production uses [workerLoop]. */
    internal fun processOneQuantumForTest(): Boolean {
        val pcm = synchronized(lock) { planQuantumLocked(sinkBufferedMs = 0) } ?: return false
        val written = writeDecodedPcm(pcm)
        synchronized(lock) {
            if (written) writtenQuanta++
            publishStatsLocked()
        }
        return written
    }

    private fun workerLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        while (running) {
            val sinkMs = bufferedPcmMs().coerceAtLeast(0)
            cachedSinkBufferedMs = sinkMs
            cachedAudioTrackUnderruns = audioTrackUnderruns().coerceAtLeast(0)
            cachedActualTrackBufferMs = actualTrackBufferMs().coerceAtLeast(0)
            val pcm = synchronized(lock) {
                if (!running) return
                removeExpiredIdleSourcesLocked()
                val target = activeTargetDelayLocked()
                if (sinkMs >= target || !hasReadyOrPlayingSourceLocked()) {
                    waitLocked(waitDurationMs(sinkMs, target))
                    null
                } else {
                    planQuantumLocked(sinkMs)
                }
            } ?: continue

            if (!writeDecodedPcm(pcm)) {
                logW("AudioTrack rejected PCM quantum")
                synchronized(lock) { waitLocked(WORKER_RETRY_MS) }
            } else {
                synchronized(lock) {
                    writtenQuanta++
                    publishStatsLocked()
                }
            }
        }
    }

    private fun planQuantumLocked(sinkBufferedMs: Int): ByteArray? {
        val contributions = ArrayList<ByteArray>(sources.size)
        var hasRealAudio = false
        var hasPlayingSource = false

        sources.values.forEach { source ->
            source.ensureDecodedQuantum()
            if (!source.started) {
                if (source.queuedAudioMs() >= source.targetDelayMs ||
                    (source.inputEnded && source.queuedAudioMs() > 0)
                ) {
                    source.started = true
                } else {
                    return@forEach
                }
            }

            hasPlayingSource = true
            val actual = source.decoded.removeFirstOrNull()
                ?: source.ensureDecodedQuantum().let { source.decoded.removeFirstOrNull() }
            if (actual != null) {
                contributions += source.smoother.acceptActual(actual)
                source.expansionMs = 0
                hasRealAudio = true
            } else if (source.active &&
                source.expansionMs < MAX_TEMPORARY_EXPANSION_MS &&
                sinkBufferedMs <= LOW_WATER_MS
            ) {
                contributions += source.smoother.expand()
                source.expansionMs += QUANTUM_MS
                pcmExpansions++
                bumpTargetLocked(source)
            }

            if (source.inputEnded && source.isDrained()) {
                source.started = false
                source.smoother.reset()
            }
        }

        removeExpiredIdleSourcesLocked()
        if (contributions.isEmpty()) {
            if (hasPlayingSource && sinkBufferedMs > LOW_WATER_MS) waitLocked(WORKER_POLL_MS.toLong())
            return null
        }
        if (!hasRealAudio && sinkBufferedMs > LOW_WATER_MS) return null
        return mixPcm(contributions)
    }

    private fun hasReadyOrPlayingSourceLocked(): Boolean = sources.values.any { source ->
        source.started || source.queuedAudioMs() >= source.targetDelayMs ||
            (source.inputEnded && source.queuedAudioMs() > 0)
    }

    private fun activeTargetDelayLocked(): Int = sources.values
        .filter { it.active || it.started || !it.isDrained() }
        .maxOfOrNull { it.targetDelayMs }
        ?: baseDelayMs

    private fun waitDurationMs(sinkMs: Int, targetMs: Int): Long = when {
        sinkMs <= LOW_WATER_MS -> WORKER_RETRY_MS
        sinkMs >= targetMs -> WORKER_POLL_MS.toLong()
        else -> (sinkMs - LOW_WATER_MS).coerceIn(1, WORKER_POLL_MS).toLong()
    }

    private fun waitLocked(ms: Long) {
        if (!running && startWorker) return
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

    private fun removeExpiredIdleSourcesLocked() {
        val now = nowNs()
        val iterator = sources.iterator()
        while (iterator.hasNext()) {
            val source = iterator.next().value
            if (source.inputEnded && source.isDrained()) {
                if (adaptiveEnabled && now - source.lastExpansionNs >= STABLE_DECAY_NS) {
                    source.targetDelayMs = baseDelayMs
                }
                if (source.endedAtNs > 0L && now - source.endedAtNs >= SOURCE_RETENTION_NS) iterator.remove()
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
            pcmExpansions = pcmExpansions,
            permanentLossConcealments = permanentLossConcealments,
            droppedFrames = sources.values.sumOf { it.droppedFrames },
            writtenQuanta = writtenQuanta,
            actualTrackBufferMs = cachedActualTrackBufferMs,
        )
    }

    private fun logW(message: String) {
        runCatching { Log.w(tag, message) }
    }

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
        var lastArrivalNs = 0L
        var lastAudioSequence = Long.MIN_VALUE
        var lastActivitySequence = Long.MIN_VALUE
        var jitterMs = 0.0
        var lastExpansionNs = Long.MIN_VALUE
        var endedAtNs = 0L
        var droppedFrames = 0L

        fun recordArrival(
            sequence: Long,
            receivedAtNs: Long,
            baseDelayMs: Int,
            maxDelayMs: Int,
            adaptive: Boolean,
        ) {
            if (lastArrivalNs > 0L && lastAudioSequence != Long.MIN_VALUE && sequence == lastAudioSequence + 1L) {
                val arrivalDeltaMs = (receivedAtNs - lastArrivalNs) / 1_000_000.0
                val deviation = kotlin.math.abs(arrivalDeltaMs - AudioCapture.FRAME_MS)
                jitterMs += (deviation - jitterMs) / RFC_JITTER_SMOOTHING
                if (adaptive) {
                    val estimated = baseDelayMs + (2.0 * jitterMs)
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
            lastArrivalNs = 0L
            lastAudioSequence = Long.MIN_VALUE
            lastActivitySequence = Long.MIN_VALUE
            jitterMs = 0.0
            targetDelayMs = nextDelayMs
            endedAtNs = 0L
            smoother.reset()
        }
    }

    companion object {
        private const val TAG = "AdaptiveInboundPlayout"
        const val QUANTUM_MS = 10
        const val QUANTUM_SAMPLES = AudioCapture.SAMPLE_RATE / 1000 * QUANTUM_MS
        const val QUANTUM_BYTES = QUANTUM_SAMPLES * 2
        private const val LOW_WATER_MS = 10
        private const val WORKER_POLL_MS = 5
        private const val WORKER_RETRY_MS = 2L
        private const val RFC_JITTER_SMOOTHING = 16.0
        private const val MAX_BUFFERED_AUDIO_FRAMES = 1_500
        private const val MAX_PERMANENT_LOSS_CONCEALMENT_FRAMES = 3
        private const val MAX_TEMPORARY_EXPANSION_MS = 60
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
        private const val OVERLAP_SAMPLES = AudioCapture.SAMPLE_RATE * 5 / 1_000
        private const val CORRELATION_SAMPLES = OVERLAP_SAMPLES
        private const val MIN_LAG = AudioCapture.SAMPLE_RATE * 25 / 10_000
        private const val MAX_LAG = AudioCapture.SAMPLE_RATE * 125 / 10_000
        private const val DEFAULT_LAG = AudioCapture.SAMPLE_RATE * 10 / 1_000
        private const val MAX_EXPANSION_QUANTA = 6
    }
}
