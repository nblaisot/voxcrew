package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyCriticalStateTest {
    @Test
    fun `silence without queued media is not latency critical`() {
        assertFalse(latencyCriticalState(false, false, false, false))
    }

    @Test
    fun `each real-time media source is latency critical`() {
        assertTrue(latencyCriticalState(true, false, false, false))
        assertTrue(latencyCriticalState(false, true, false, false))
        assertTrue(latencyCriticalState(false, false, true, false))
        assertTrue(latencyCriticalState(false, false, false, true))
    }
}
