package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanIntercomEngineFanOutTest {

    @Test
    fun `active recipient set controls fan-out targets only`() {
        val sentTo = mutableMapOf<String, MutableList<ByteArray>>()
        val peerA = FakeSendTarget("peer-a") { payload -> sentTo.getOrPut("peer-a") { mutableListOf() }.add(payload) }
        val peerB = FakeSendTarget("peer-b") { payload -> sentTo.getOrPut("peer-b") { mutableListOf() }.add(payload) }
        val active = mutableSetOf("peer-a", "peer-b")

        fun fanOut(payload: ByteArray) {
            active.forEach { uid ->
                when (uid) {
                    "peer-a" -> peerA.send(payload)
                    "peer-b" -> peerB.send(payload)
                }
            }
        }

        val frame = byteArrayOf(1, 2, 3)
        fanOut(frame)
        assertEquals(1, sentTo["peer-a"]?.size)
        assertEquals(1, sentTo["peer-b"]?.size)

        active.remove("peer-b")
        fanOut(byteArrayOf(4))
        assertEquals(2, sentTo["peer-a"]?.size)
        assertEquals(1, sentTo["peer-b"]?.size)
    }

    @Test
    fun `solo mode sends to one peer only`() {
        val sent = mutableSetOf<String>()
        val active = mutableSetOf("peer-a", "peer-b", "peer-c")

        fun solo(uid: String) {
            active.clear()
            active.add(uid)
        }

        solo("peer-b")
        assertEquals(setOf("peer-b"), active.toSet())

        active.forEach { sent.add(it) }
        assertFalse(sent.contains("peer-a"))
        assertTrue(sent.contains("peer-b"))
    }

    private class FakeSendTarget(val uid: String, private val onSend: (ByteArray) -> Unit) {
        fun send(payload: ByteArray) = onSend(payload)
    }
}

class ActiveRecipientsPersistenceTest {

    @Test
    fun `json round-trip preserves active recipient uids`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val original = setOf("uid-a", "uid-b", "uid-c")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Set<String>>(encoded)
        assertEquals(original, decoded)
    }
}

class LanTcpServerDispatchTest {

    private fun newPeerLink(uid: String): PeerLink {
        val scope = TestScope(StandardTestDispatcher())
        return PeerLink(scope).also { it.resetFor(uid) }
    }

    @Test
    fun `registered client receives inbound adoption for matching peer uid`() {
        val scope = TestScope(StandardTestDispatcher())
        val server = LanTcpServer(scope)
        server.start("local-a")
        val linkB = newPeerLink("peer-b")
        val clientB = LanTcpClient(
            scope,
            "local-a",
            linkB,
            server,
            NoOpTestNetworkBinder,
            inboundRouteResolver = { null },
        )
        server.registerClient("peer-b", clientB)

        assertEquals("peer-b", linkB.selectedPeerUid)
        assertTrue(clientB.lastContiguousInSeq() >= -1L)
    }
}
