package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendBufferTest {

    @Test
    fun `add keeps frames in insertion order`() {
        val buffer = SendBuffer()
        buffer.add(0, byteArrayOf(1))
        buffer.add(1, byteArrayOf(2))
        buffer.add(2, byteArrayOf(3))

        assertEquals(listOf(0L, 1L, 2L), buffer.replayFrom(-1).map { it.seq })
    }

    @Test
    fun `trimTo drops acknowledged frames only`() {
        val buffer = SendBuffer()
        (0L..4L).forEach { buffer.add(it, byteArrayOf(it.toByte())) }

        buffer.trimTo(2)

        assertEquals(listOf(3L, 4L), buffer.replayFrom(-1).map { it.seq })
    }

    @Test
    fun `replayFrom returns only frames strictly after the given seq`() {
        val buffer = SendBuffer()
        (0L..4L).forEach { buffer.add(it, byteArrayOf(it.toByte())) }

        assertEquals(listOf(3L, 4L), buffer.replayFrom(2).map { it.seq })
        assertEquals(emptyList<Long>(), buffer.replayFrom(4).map { it.seq })
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), buffer.replayFrom(-1).map { it.seq })
    }

    @Test
    fun `resume after reconnect replays exactly the gap the peer is missing`() {
        val buffer = SendBuffer()
        // Sender transmitted seq 0..2 before the link dropped; peer only confirmed 0..1.
        (0L..2L).forEach { buffer.add(it, byteArrayOf(it.toByte())) }
        val peerAnnouncedLastContiguousSeq = 1L

        buffer.trimTo(peerAnnouncedLastContiguousSeq)
        val toReplay = buffer.replayFrom(peerAnnouncedLastContiguousSeq)

        assertEquals(listOf(2L), toReplay.map { it.seq })
    }

    @Test
    fun `oldest frames are dropped once the byte cap is exceeded`() {
        val buffer = SendBuffer(maxBytes = 10)
        buffer.add(0, ByteArray(6))
        buffer.add(1, ByteArray(6))
        buffer.add(2, ByteArray(6))

        val remaining = buffer.replayFrom(-1).map { it.seq }
        assertTrue(remaining.isNotEmpty())
        assertEquals(listOf(2L), remaining)
        assertTrue(buffer.byteSize() <= 10 || buffer.size() == 1)
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = SendBuffer()
        buffer.add(0, byteArrayOf(1))
        buffer.clear()

        assertEquals(0, buffer.size())
        assertEquals(emptyList<Long>(), buffer.replayFrom(-1).map { it.seq })
    }
}
