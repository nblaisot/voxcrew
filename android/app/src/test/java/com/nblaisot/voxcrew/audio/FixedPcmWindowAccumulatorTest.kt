package com.nblaisot.voxcrew.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FixedPcmWindowAccumulatorTest {
    @Test
    fun producesContiguousReusableWindowsAcrossFrameBoundaries() {
        val accumulator = FixedPcmWindowAccumulator(windowSize = 5)
        val windows = mutableListOf<ShortArray>()

        accumulator.append(shortArrayOf(0, 1, 2)) { windows += it.copyOf() }
        accumulator.append(shortArrayOf(3, 4, 5, 6)) { windows += it.copyOf() }
        accumulator.append(shortArrayOf(7, 8, 9, 10)) { windows += it.copyOf() }

        assertEquals(2, windows.size)
        assertArrayEquals(shortArrayOf(0, 1, 2, 3, 4), windows[0])
        assertArrayEquals(shortArrayOf(5, 6, 7, 8, 9), windows[1])
    }
}
