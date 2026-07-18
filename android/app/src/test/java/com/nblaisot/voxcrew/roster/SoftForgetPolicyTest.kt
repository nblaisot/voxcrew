package com.nblaisot.voxcrew.roster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftForgetPolicyTest {
    @Test
    fun `forgotten offline peer disappears from visible set`() {
        assertEquals(
            setOf("a"),
            SoftForgetPolicy.visibleUids(
                cachedUids = setOf("a", "b"),
                livePeerUids = emptySet(),
                forgottenUid = "b",
            ),
        )
    }

    @Test
    fun `forgotten live peer stays visible from discovery`() {
        assertEquals(
            setOf("a", "b"),
            SoftForgetPolicy.visibleUids(
                cachedUids = setOf("a", "b"),
                livePeerUids = setOf("b"),
                forgottenUid = "b",
            ),
        )
    }

    @Test
    fun `skip cache only while peer remains live`() {
        val skip = SoftForgetPolicy.skipCacheAfterTick(
            previousSkip = setOf("b", "c"),
            livePeerUids = setOf("b"),
        )
        assertEquals(setOf("b"), skip)
        assertFalse(SoftForgetPolicy.shouldPersistToCache("b", skip))
        assertTrue(SoftForgetPolicy.shouldPersistToCache("c", skip))
    }
}
