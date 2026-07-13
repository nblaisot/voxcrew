package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioFrameIoTest {
    @Test
    fun partialReadsAssembleOneExactFrame() {
        val frame = ByteArray(640)
        val calls = mutableListOf<Pair<Int, Int>>()
        val chunks = ArrayDeque(listOf(100, 220, 320))

        fillPcmFrame(frame) { offset, remaining ->
            calls += offset to remaining
            chunks.removeFirst()
        }

        assertEquals(listOf(0 to 640, 100 to 540, 320 to 320), calls)
    }

    @Test
    fun readWithoutProgressFailsTheGraph() {
        assertThrows(IllegalStateException::class.java) {
            fillPcmFrame(ByteArray(640)) { _, _ -> 0 }
        }
    }

    @Test
    fun partialWritesDrainTheCompleteFrame() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val chunks = ArrayDeque(listOf(160, 160, 320))

        drainPcm(640) { offset, remaining ->
            calls += offset to remaining
            chunks.removeFirst()
        }

        assertEquals(listOf(0 to 640, 160 to 480, 320 to 320), calls)
    }

    @Test
    fun failedWriteFailsTheGraph() {
        assertThrows(IllegalStateException::class.java) {
            drainPcm(640) { _, _ -> -6 }
        }
    }
}
