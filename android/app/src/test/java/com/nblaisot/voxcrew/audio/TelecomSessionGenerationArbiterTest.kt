package com.nblaisot.voxcrew.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelecomSessionGenerationArbiterTest {
    @Test
    fun `concurrent startup requests acquire one Telecom generation`() {
        val arbiter = TelecomSessionGenerationArbiter()
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(8)
        val leases = java.util.Collections.synchronizedList(
            mutableListOf<TelecomSessionGenerationArbiter.Lease>(),
        )

        repeat(8) {
            executor.execute {
                ready.countDown()
                start.await()
                leases += arbiter.acquire()
                finished.countDown()
            }
        }
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(1, leases.count { it.isNew })
        assertEquals(1, leases.map { it.generation }.distinct().size)
    }

    @Test
    fun `invalidated callbacks cannot affect the replacement generation`() {
        val arbiter = TelecomSessionGenerationArbiter()
        val first = arbiter.acquire().generation
        arbiter.invalidate()
        val second = arbiter.acquire().generation

        assertNotEquals(first, second)
        assertFalse(arbiter.isCurrent(first))
        assertTrue(arbiter.isCurrent(second))
        assertFalse(arbiter.finish(first))
        assertTrue(arbiter.isCurrent(second))
        assertTrue(arbiter.finish(second))
        assertFalse(arbiter.hasActiveGeneration())
    }
}
