package com.nblaisot.voxcrew.lanlink

/**
 * Ring buffer of sent-but-possibly-unacknowledged audio frames for a single peer
 * conversation. This is the heart of the "progressive download" resume model:
 * while disconnected, frames keep accumulating here (capture never blocks); once
 * reconnected, everything past the peer's last-known contiguous seq is replayed
 * so no audio is lost, only delayed.
 */
class SendBuffer(private val maxBytes: Int = DEFAULT_MAX_BYTES) {
    data class Entry(val seq: Long, val data: ByteArray, val enqueuedAtMs: Long = System.currentTimeMillis())

    private val frames = ArrayDeque<Entry>()
    private var totalBytes = 0L

    @Synchronized
    fun add(seq: Long, data: ByteArray) {
        frames.addLast(Entry(seq, data))
        totalBytes += data.size
        while (totalBytes > maxBytes && frames.size > 1) {
            totalBytes -= frames.removeFirst().data.size
        }
    }

    /** Age of the oldest still-unacknowledged frame, or null if the buffer is empty. */
    @Synchronized
    fun oldestEnqueuedAtMs(): Long? = frames.firstOrNull()?.enqueuedAtMs

    /** Drops all frames the peer has confirmed receiving (seq <= ackedSeq). */
    @Synchronized
    fun trimTo(ackedSeq: Long) {
        while (frames.isNotEmpty() && frames.first().seq <= ackedSeq) {
            totalBytes -= frames.removeFirst().data.size
        }
    }

    /** Frames strictly after [seq], in original order — used to resume a connection. */
    @Synchronized
    fun replayFrom(seq: Long): List<Entry> = frames.filter { it.seq > seq }

    @Synchronized
    fun clear() {
        frames.clear()
        totalBytes = 0
    }

    @Synchronized
    fun size(): Int = frames.size

    @Synchronized
    fun byteSize(): Long = totalBytes

    companion object {
        /** ~2 minutes of 16 kHz mono 16-bit PCM (32 KB/s). */
        const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024
    }
}
