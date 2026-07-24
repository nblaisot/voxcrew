package com.nblaisot.voxcrew.lanlink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTcpSessionLifecycleTest {
    @Test
    fun `concurrent close invokes callback once outside session monitor`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callbacks = AtomicInteger()
        val callbackHeldMonitor = AtomicBoolean(true)
        val callbackDone = CountDownLatch(1)
        lateinit var session: LanTcpSession
        session = LanTcpSession(
            scope = scope,
            peerUid = "peer",
            generation = 7L,
            retryKey = "LAN|host|port|network",
            socket = Socket(),
            out = DataOutputStream(ByteArrayOutputStream()),
            input = DataInputStream(ByteArrayInputStream(byteArrayOf())),
            onFrame = { _, _ -> },
            onClosed = {
                callbackHeldMonitor.set(Thread.holdsLock(session))
                callbacks.incrementAndGet()
                callbackDone.countDown()
            },
        )
        val start = CountDownLatch(1)
        val threads = List(12) {
            Thread {
                start.await()
                session.close()
            }.apply { start() }
        }

        start.countDown()
        threads.forEach { it.join(2_000L) }

        assertTrue("close callback did not complete", callbackDone.await(2, TimeUnit.SECONDS))
        assertEquals(1, callbacks.get())
        assertFalse("callback ran while holding the session monitor", callbackHeldMonitor.get())
        assertTrue(threads.none { it.isAlive })
        scope.cancel()
    }

    @Test
    fun `session confirmation is exactly once and impossible after close`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = LanTcpSession(
            scope = scope,
            peerUid = "peer",
            generation = 8L,
            retryKey = "VPN|host|port|network",
            socket = Socket(),
            out = DataOutputStream(ByteArrayOutputStream()),
            input = DataInputStream(ByteArrayInputStream(byteArrayOf())),
            onFrame = { _, _ -> },
            onClosed = {},
        )

        assertTrue(session.confirm())
        assertFalse(session.confirm())
        session.close()
        assertFalse(session.confirm())
        scope.cancel()
    }
}
