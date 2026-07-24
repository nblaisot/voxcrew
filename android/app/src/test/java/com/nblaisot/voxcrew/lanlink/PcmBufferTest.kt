package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmBufferTest {
    @Test
    fun littleEndianConversionIsBitExact() {
        val output = ShortArray(4)

        pcm16LeToShorts(
            byteArrayOf(0x34, 0x12, 0x00, 0x80.toByte(), 0xff.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte()),
            output,
        )

        assertArrayEquals(shortArrayOf(0x1234, Short.MIN_VALUE, Short.MAX_VALUE, -1), output)
    }

    @Test
    fun preRollKeepsNewestFramesAndDrainsChronologically() {
        val preRoll = PcmPreRoll(capacity = 3, frameBytes = 1)
        listOf(1, 2, 3, 4).forEach { preRoll.push(byteArrayOf(it.toByte())) }
        val drained = mutableListOf<Int>()

        preRoll.drain { drained += it[0].toInt() }

        assertEquals(listOf(2, 3, 4), drained)
        preRoll.drain { error("already empty") }
    }
}
