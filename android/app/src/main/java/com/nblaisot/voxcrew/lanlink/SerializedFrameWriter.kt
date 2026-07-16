package com.nblaisot.voxcrew.lanlink

import java.util.concurrent.atomic.AtomicBoolean
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
) {
    private val running = AtomicBoolean(false)
    private val frames = Channel<LanFrame>(Channel.BUFFERED)
    private var writerJob: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        writerJob = scope.launch(dispatcher) {
            try {
                for (frame in frames) write(frame)
            } catch (error: Throwable) {
                if (running.compareAndSet(true, false)) {
                    frames.close(error)
                    onFailure(error)
                }
            }
        }
    }

    /** Returns immediately. False means the transport must close/reconnect and replay. */
    fun tryWrite(frame: LanFrame): Boolean = running.get() && frames.trySend(frame).isSuccess

    fun stop() {
        if (!running.getAndSet(false)) return
        frames.close()
        writerJob?.cancel()
        writerJob = null
    }
}
