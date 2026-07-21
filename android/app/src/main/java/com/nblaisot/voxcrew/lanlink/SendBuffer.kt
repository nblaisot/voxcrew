package com.nblaisot.voxcrew.lanlink

/**
 * Ring buffer of sent-but-possibly-unacknowledged audio and media-boundary frames for a single peer
 * conversation. This is the heart of the "progressive download" resume model:
 * while disconnected, frames keep accumulating here (capture never blocks); once
 * reconnected, everything past the peer's last-known contiguous seq is replayed.
 * Frames older than [DEFAULT_MAX_AGE_MS] or beyond the byte cap are evicted.
 */
class SendBuffer(private val maxBytes: Int = DEFAULT_MAX_BYTES) {
    enum class Kind { AUDIO, MEDIA_ACTIVE, MEDIA_INACTIVE }

    data class Entry(
        val seq: Long,
        val data: ByteArray,
        val kind: Kind = Kind.AUDIO,
        val enqueuedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun toFrame(): LanFrame = when (kind) {
            Kind.AUDIO -> LanFrame.Audio(seq, data)
            Kind.MEDIA_ACTIVE -> LanFrame.MediaActivity(seq, true)
            Kind.MEDIA_INACTIVE -> LanFrame.MediaActivity(seq, false)
        }
    }

    private val frames = ArrayDeque<Entry>()
    private var totalBytes = 0L

    @Synchronized
    fun add(
        seq: Long,
        data: ByteArray,
        enqueuedAtMs: Long = System.currentTimeMillis(),
        kind: Kind = Kind.AUDIO,
    ) {
        frames.addLast(Entry(seq, data, kind, enqueuedAtMs))
        totalBytes += entrySize(data, kind)
        while (totalBytes > maxBytes && frames.size > 1) {
            totalBytes -= sizeOf(frames.removeFirst())
        }
    }

    /** Age of the oldest still-unacknowledged frame, or null if the buffer is empty. */
    @Synchronized
    fun oldestEnqueuedAtMs(): Long? = frames.firstOrNull()?.enqueuedAtMs

    /** Sequence of the oldest still-buffered frame, or null if the buffer is empty. */
    @Synchronized
    fun firstSeq(): Long? = frames.firstOrNull()?.seq

    /** Drops all frames the peer has confirmed receiving (seq <= ackedSeq). */
    @Synchronized
    fun trimTo(ackedSeq: Long) {
        while (frames.isNotEmpty() && frames.first().seq <= ackedSeq) {
            totalBytes -= sizeOf(frames.removeFirst())
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
    fun audioFrameCount(): Int = frames.count { it.kind == Kind.AUDIO }

    @Synchronized
    fun byteSize(): Long = totalBytes

    /** Drops frames enqueued before [nowMs] - [maxAgeMs]. Returns the number removed. */
    @Synchronized
    fun expireOlderThan(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMs - maxAgeMs
        var dropped = 0
        while (frames.isNotEmpty() && frames.first().enqueuedAtMs < cutoff) {
            totalBytes -= sizeOf(frames.removeFirst())
            dropped++
        }
        return dropped
    }

    companion object {
        /** ~2 minutes of 16 kHz mono 16-bit PCM (32 KB/s). */
        const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024

        /** Unacknowledged frames older than this are discarded (see [expireOlderThan]). */
        const val DEFAULT_MAX_AGE_MS = 30_000L
    }

    private fun sizeOf(entry: Entry): Int = entrySize(entry.data, entry.kind)

    private fun entrySize(data: ByteArray, kind: Kind): Int =
        if (kind == Kind.AUDIO) data.size else 1
}
