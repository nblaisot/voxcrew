package com.nblaisot.voxcrew.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxSensitivityTest {
    @Test
    fun rejectsOutOfRangeLevels() {
        assertThrows { VoxSensitivity(0) }
        assertThrows { VoxSensitivity(6) }
    }

    @Test
    fun acceptsBoundaryLevels() {
        assertEquals(1, VoxSensitivity(1).level)
        assertEquals(5, VoxSensitivity(5).level)
    }

    @Test
    fun coerceClampsIntoRange() {
        assertEquals(1, VoxSensitivity.coerce(-10).level)
        assertEquals(5, VoxSensitivity.coerce(99).level)
        assertEquals(3, VoxSensitivity.coerce(3).level)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
