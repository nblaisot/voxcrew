@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nblaisot.voxcrew.lanlink

import com.nblaisot.voxcrew.relay.RelayClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerConnectionCloudRedialTest {

    private class FakeCloudTransport : FrameTransport {
        override val label: String = PathLabels.CLOUD
        override fun sendFrame(frame: LanFrame) = Unit
        override fun dropAndRetry() = Unit
        override fun stop() = Unit
    }

    @Test
    fun `cloud link death clears sticky fail and redials after cooldown`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val peerScope = CoroutineScope(dispatcher + SupervisorJob())
        val relay = mockk<RelayClient>(relaxed = true)
        every { relay.isReady() } returns true
        coEvery { relay.dial(any()) } returns false

        val server = LanTcpServer(peerScope)
        val conn = PeerConnection(
            peerUid = "peer",
            scope = peerScope,
            localUid = "local",
            lanServer = server,
            networkSocketBinder = NoOpTestNetworkBinder,
            inboundRouteResolver = { null },
            isStillWanted = { true },
            overlayPeerProvider = { null },
            lanPeerProvider = { null },
            relayClientProvider = { relay },
        )
        try {
            conn.start()
            runCurrent()
            val transport = FakeCloudTransport()
            conn.peerLink.onHandshakeComplete(transport, "peer", -1)
            runCurrent()
            assertTrue(conn.linkState.value is PeerLink.LinkState.Connected)
            assertFalse(conn.cloudDialFailedForTest())

            conn.peerLink.onDisconnected(transport, "peer")
            runCurrent()
            assertTrue(conn.cloudDialFailedForTest())
            coVerify(exactly = 0) { relay.dial(any()) }

            advanceTimeBy(5_000L)
            runCurrent()

            coVerify(atLeast = 1) { relay.dial("peer") }
            assertTrue(conn.cloudDialFailedForTest())
        } finally {
            conn.stop()
            peerScope.cancel()
        }
    }

    @Test
    fun `cloud dial fail clears sticky and redials after cooldown`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val peerScope = CoroutineScope(dispatcher + SupervisorJob())
        val relay = mockk<RelayClient>(relaxed = true)
        every { relay.isReady() } returns true
        coEvery { relay.dial(any()) } returns false

        val server = LanTcpServer(peerScope)
        val conn = PeerConnection(
            peerUid = "peer",
            scope = peerScope,
            localUid = "local",
            lanServer = server,
            networkSocketBinder = NoOpTestNetworkBinder,
            inboundRouteResolver = { null },
            isStillWanted = { true },
            overlayPeerProvider = { null },
            lanPeerProvider = { null },
            relayClientProvider = { relay },
        )
        try {
            conn.start()
            runCurrent()
            conn.promoteToCloud()
            runCurrent()
            assertTrue(conn.cloudDialFailedForTest())
            coVerify(exactly = 1) { relay.dial("peer") }

            advanceTimeBy(5_000L)
            runCurrent()

            coVerify(atLeast = 2) { relay.dial("peer") }
        } finally {
            conn.stop()
            peerScope.cancel()
        }
    }
}
