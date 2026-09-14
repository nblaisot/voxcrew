package com.nblaisot.voxcrew.lanlink

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Ordered, non-blocking boundary between protocol callers and socket writes.
 * Callers only enqueue; exactly one IO coroutine performs the potentially blocking write.
 */
internal class SerializedFrameWriter(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val write: (LanFrame) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onMetrics: (WriterMetrics) -> Unit = { },
    private val clockNs: () -> Long = System::nanoTime,
) {
    private data class QueuedFrame(val frame: LanFrame, val enqueuedAtNs: Long)

    private val running = AtomicBoolean(false)
    private val frames = Channel<QueuedFrame>(WRITER_QUEUE_CAPACITY)
    private val queuedFrames = AtomicInteger(0)
    private val highWaterFrames = AtomicInteger(0)
    private val writtenFrames = AtomicLong(0L)
    private var writerJob: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        writerJob = scope.launch(dispatcher) {
            try {
                for (queued in frames) {
                    val depth = queuedFrames.decrementAndGet().coerceAtLeast(0)
                    val writeStartedNs = clockNs()
                    write(queued.frame)
                    val completed = writtenFrames.incrementAndGet()
                    val ageMs = elapsedMs(queued.enqueuedAtNs)
                    val writeMs = elapsedMs(writeStartedNs)
                    if (completed == 1L || completed % METRICS_INTERVAL == 0L ||
                        ageMs >= QUEUE_AGE_WARNING_MS || writeMs >= WRITE_WARNING_MS
                    ) {
                        onMetrics(
                            WriterMetrics(
                                queuedFrames = depth,
                                highWaterFrames = highWaterFrames.get(),
                                oldestCompletedAgeMs = ageMs,
                                writeDurationMs = writeMs,
                                writtenFrames = completed,
                                rejected = false,
                            ),
                        )
                    }
                }
            } catch (error: Throwable) {
                if (running.compareAndSet(true, false)) {
                    frames.close(error)
                    onFailure(error)
                }
            }
        }
    }

    /** Returns immediately. False means the transport must close/reconnect and replay. */
    fun tryWrite(frame: LanFrame): Boolean {
        if (!running.get()) return false
        val depth = queuedFrames.incrementAndGet()
        highWaterFrames.accumulateAndGet(depth, ::maxOf)
        val accepted = frames.trySend(QueuedFrame(frame, clockNs())).isSuccess
        if (!accepted) {
            queuedFrames.decrementAndGet()
            onMetrics(
                WriterMetrics(
                    queuedFrames = queuedFrames.get().coerceAtLeast(0),
                    highWaterFrames = highWaterFrames.get(),
                    oldestCompletedAgeMs = 0L,
                    writeDurationMs = 0L,
                    writtenFrames = writtenFrames.get(),
                    rejected = true,
                ),
            )
        }
        return accepted
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        frames.close()
        writerJob?.cancel()
        writerJob = null
    }

    private fun elapsedMs(startNs: Long): Long = ((clockNs() - startNs) / 1_000_000L).coerceAtLeast(0L)

    companion object {
        private const val WRITER_QUEUE_CAPACITY = 64
        private const val METRICS_INTERVAL = 100L
        private const val QUEUE_AGE_WARNING_MS = 60L
        private const val WRITE_WARNING_MS = 20L
    }
}

internal data class WriterMetrics(
    val queuedFrames: Int,
    val highWaterFrames: Int,
    val oldestCompletedAgeMs: Long,
    val writeDurationMs: Long,
    val writtenFrames: Long,
    val rejected: Boolean,
)
