package com.nblaisot.voxcrew.signaling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

const val SIGNALING_PROTOCOL_VERSION = 1

@Serializable
data class SignalingEnvelope(
    val version: Int = SIGNALING_PROTOCOL_VERSION,
    val type: String,
    val requestId: String? = null,
    val sessionId: String? = null,
    val senderId: String? = null,
    val recipientId: String? = null,
    val payload: JsonObject = buildJsonObject {},
)

object SignalingMessageTypes {
    const val AUTHENTICATE = "authenticate"
    const val AUTHENTICATED = "authenticated"
    const val AUTHENTICATION_ERROR = "authentication_error"
    const val CREATE_SESSION = "create_session"
    const val SESSION_CREATED = "session_created"
    const val JOIN_SESSION = "join_session"
    const val SESSION_JOINED = "session_joined"
    const val PARTICIPANT_JOINED = "participant_joined"
    const val PARTICIPANT_LEFT = "participant_left"
    const val LEAVE_SESSION = "leave_session"
    const val PING = "ping"
    const val PONG = "pong"
    const val ERROR = "error"
    const val PRESENCE_REGISTER = "presence_register"
    const val PRESENCE_HEARTBEAT = "presence_heartbeat"
    const val PRESENCE_SNAPSHOT = "presence_snapshot"
    const val PRESENCE_UPDATED = "presence_updated"
    const val PRESENCE_OFFLINE = "presence_offline"
    const val P2P_CONNECT_REQUEST = "p2p_connect_request"
    const val P2P_ENDPOINTS = "p2p_endpoints"
}

val signalingJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun jsonPayload(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
}

fun jsonPayloadRaw(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, v) }
}
