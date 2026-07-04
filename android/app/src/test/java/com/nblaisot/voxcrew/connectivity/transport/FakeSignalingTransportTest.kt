package com.nblaisot.voxcrew.connectivity.transport

import com.nblaisot.voxcrew.connectivity.transport.FakeSignalingTransport
import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.signaling.jsonPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSignalingTransportTest {
    @Test
    fun connectAndSend() = runTest {
        val transport = FakeSignalingTransport()
        val session = SessionDescriptor("s1", "user-a", sessionSecret = "secret")
        val gen = GenerationId(1)
        transport.connect(session, gen)
        assertTrue(transport.connected)
        transport.send(
            com.nblaisot.voxcrew.signaling.SignalingEnvelope(
                type = SignalingMessageTypes.PING,
                requestId = "r1",
                payload = jsonPayload("timestamp" to "1"),
            ),
        )
        assertEquals(1, transport.sent.size)
    }
}
