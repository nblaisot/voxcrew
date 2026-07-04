package com.nblaisot.voxcrew.signaling

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    @Test
    fun authenticateEnvelopeJson() {
        val json = signalingJson.encodeToString(
            SignalingEnvelope.serializer(),
            SignalingEnvelope(
                type = SignalingMessageTypes.AUTHENTICATE,
                requestId = "550e8400-e29b-41d4-a716-446655440000",
                payload = buildJsonObject {
                    put("authKind", JsonPrimitive("firebase"))
                    put("token", JsonPrimitive("eyJhbG.test.token"))
                },
            ),
        )
        println("AUTH_JSON=$json")
        org.junit.Assert.assertTrue(json.contains("authenticate"))
    }
}
