package com.nblaisot.voxcrew.roster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairSessionIdsTest {
    @Test
    fun pairSessionId_isDeterministicRegardlessOfOrder() {
        val a = PairSessionIds.forUids("uid-a", "uid-b")
        val b = PairSessionIds.forUids("uid-b", "uid-a")
        assertEquals(a, b)
    }

    @Test
    fun pairSessionId_differsForDifferentPairs() {
        val ab = PairSessionIds.forUids("uid-a", "uid-b")
        val ac = PairSessionIds.forUids("uid-a", "uid-c")
        assertNotEquals(ab, ac)
    }
}
