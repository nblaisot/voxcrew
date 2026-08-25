@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTcpClientConnectedWritableTest {

    @Test
    fun `sendFrame demotes Connected when TCP session is gone`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val peerLink = PeerLink(scope)
        peerLink.resetFor("peer")
        val server = LanTcpServer(scope)
        val client = LanTcpClient(
            scope = scope,
            localUid = "local",
            peerLink = peerLink,
            server = server,
            networkSocketBinder = NoOpTestNetworkBinder,
            inboundRouteResolver = { null },
        )
        try {
            // Simulate Connected via this transport without an open TCP session.
            peerLink.onHandshakeComplete(client, "peer", -1)
            runCurrent()
            assertTrue(peerLink.state.value is PeerLink.LinkState.Connected)
            assertTrue(peerLink.isActiveTransport(client))

            client.sendFrame(LanFrame.Ping(1L))
            runCurrent()

            assertTrue(peerLink.state.value is PeerLink.LinkState.Disconnected)
        } finally {
            client.stop()
            peerLink.clear()
            scope.cancel()
        }
    }
}
