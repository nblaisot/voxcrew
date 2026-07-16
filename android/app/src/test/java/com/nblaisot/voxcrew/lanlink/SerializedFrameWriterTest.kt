package com.nblaisot.voxcrew.lanlink

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedFrameWriterTest {
    @Test
    fun `writes are ordered and never run on the caller thread`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callerThread = Thread.currentThread()
        val writeThread = AtomicReference<Thread>()
        val written = CopyOnWriteArrayList<LanFrame>()
        val completed = CountDownLatch(3)
        val writer = SerializedFrameWriter(
            scope = scope,
            write = { frame ->
                writeThread.compareAndSet(null, Thread.currentThread())
                written += frame
                completed.countDown()
            },
            onFailure = { throw AssertionError(it) },
        )

        writer.start()
        assertTrue(writer.tryWrite(LanFrame.MediaActivity(0, true)))
        assertTrue(writer.tryWrite(LanFrame.Audio(1, byteArrayOf(7))))
        assertTrue(writer.tryWrite(LanFrame.MediaActivity(2, false)))

        assertTrue("writer did not drain", completed.await(2, TimeUnit.SECONDS))
        assertNotEquals(callerThread, writeThread.get())
        assertEquals(
            listOf(
                LanFrame.MediaActivity::class,
                LanFrame.Audio::class,
                LanFrame.MediaActivity::class,
            ),
            written.map { it::class },
        )

        writer.stop()
        scope.cancel()
    }

    @Test
    fun `write failure closes the writer without escaping to the caller`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failure = AtomicReference<Throwable>()
        val failed = CountDownLatch(1)
        val writer = SerializedFrameWriter(
            scope = scope,
            write = { throw IOException("socket closed") },
            onFailure = { error ->
                failure.set(error)
                failed.countDown()
            },
        )

        writer.start()
        assertTrue(writer.tryWrite(LanFrame.MediaActivity(0, true)))
        assertTrue("failure was not reported", failed.await(2, TimeUnit.SECONDS))
        assertEquals("socket closed", failure.get()?.message)
        assertFalse(writer.tryWrite(LanFrame.MediaActivity(1, false)))

        writer.stop()
        scope.cancel()
    }
}
