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
    fun `cloud link death force dials immediately`() = runTest {
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
            assertFalse(conn.cloudAwaitMatchForTest())

            conn.peerLink.onDisconnected(transport, "peer")
            runCurrent()

            // Immediate force dial — no 5s sticky suppress.
            coVerify(atLeast = 1) { relay.dial("peer") }
            assertTrue(conn.cloudAwaitMatchForTest())
        } finally {
            conn.stop()
            peerScope.cancel()
        }
    }

    @Test
    fun `after dial_fail unforced promote is parked but force dials again`() = runTest {
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
            conn.promoteToCloud(force = true)
            runCurrent()
            assertTrue(conn.cloudAwaitMatchForTest())
            coVerify(exactly = 1) { relay.dial("peer") }

            // USE_CLOUD-style unforced tick must not spam.
            conn.promoteToCloud(force = false)
            runCurrent()
            coVerify(exactly = 1) { relay.dial("peer") }

            // roster_match-style force wakes immediately.
            conn.promoteToCloud(force = true)
            runCurrent()
            coVerify(exactly = 2) { relay.dial("peer") }
        } finally {
            conn.stop()
            peerScope.cancel()
        }
    }

    @Test
    fun `safety net force dials every 3s while parked`() = runTest {
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
            conn.promoteToCloud(force = true)
            runCurrent()
            coVerify(exactly = 1) { relay.dial("peer") }
            assertTrue(conn.cloudAwaitMatchForTest())

            advanceTimeBy(3_000L)
            runCurrent()
            coVerify(atLeast = 2) { relay.dial("peer") }
        } finally {
            conn.stop()
            peerScope.cancel()
        }
    }

    @Test
    fun `applyPathTargets with cloud available uses cloud not sticky clear`() = runTest {
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
            // First dial fails and parks.
            conn.promoteToCloud(force = true)
            runCurrent()
            assertTrue(conn.cloudAwaitMatchForTest())

            // Policy still sees Cloud endpoint (no sticky hasCloudEndpoint=false).
            conn.applyPathTargets(
                lanPeer = null,
                overlayPeer = null,
                cloudAvailable = true,
            )
            runCurrent()
            // Unforced USE_CLOUD respects park — still one dial.
            coVerify(exactly = 1) { relay.dial("peer") }
            assertTrue(conn.cloudAwaitMatchForTest())

            // Event wake still works.
            conn.promoteToCloud(force = true)
            runCurrent()
            coVerify(exactly = 2) { relay.dial("peer") }
        } finally {
            conn.stop()
            peerScope.cancel()
        }
    }
}
