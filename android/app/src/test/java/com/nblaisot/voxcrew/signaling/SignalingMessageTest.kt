package com.nblaisot.voxcrew.signaling

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalingMessageTest {
    @Test
    fun roundTripEnvelope() {
        val original = SignalingEnvelope(
            type = SignalingMessageTypes.PING,
            requestId = "550e8400-e29b-41d4-a716-446655440000",
            payload = jsonPayload("timestamp" to "123"),
        )
        val json = signalingJson.encodeToString(SignalingEnvelope.serializer(), original)
        val decoded = signalingJson.decodeFromString(SignalingEnvelope.serializer(), json)
        assertEquals(original.type, decoded.type)
        assertEquals(original.requestId, decoded.requestId)
    }
}
