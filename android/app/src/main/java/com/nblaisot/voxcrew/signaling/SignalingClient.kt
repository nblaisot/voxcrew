package com.nblaisot.voxcrew.signaling

import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.connectivity.transport.CloudRunSignalingTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * High-level cloud signaling facade (session API + WebRTC relay).
 * Low-level transport: [CloudRunSignalingTransport].
 */
class SignalingClient(
    baseUrl: String,
    authRepository: AuthRepository,
    private val transport: CloudRunSignalingTransport = CloudRunSignalingTransport(baseUrl, authRepository),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(SignalingUiState())
    val state: StateFlow<SignalingUiState> = _state.asStateFlow()

    val incoming: SharedFlow<SignalingEnvelope> = transport.incomingMessages

    init {
        scope.launch {
            transport.incomingMessages.collect { applyEnvelope(it) }
        }
        scope.launch {
            transport.state.collect { ts ->
                _state.update { it.copy(connectionState = ts.connectionState, lastError = ts.lastError) }
            }
        }
    }

    fun connect() = transport.connectCloud()

    fun disconnect() = transport.disconnectAll()

    suspend fun createSession(name: String? = null): Result<String> {
        val requestId = UUID.randomUUID().toString()
        transport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.CREATE_SESSION,
                requestId = requestId,
                payload = buildJsonObject { name?.let { put("name", JsonPrimitive(it)) } },
            ),
        )
        return waitForSessionId(requestId)
    }

    suspend fun joinSession(sessionId: String): Result<Unit> {
        val requestId = UUID.randomUUID().toString()
        transport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.JOIN_SESSION,
                requestId = requestId,
                payload = jsonPayload("sessionId" to sessionId),
            ),
        )
        return waitForJoin(requestId)
    }

    suspend fun leaveSession() {
        val sessionId = _state.value.sessionId ?: return
        transport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.LEAVE_SESSION,
                requestId = UUID.randomUUID().toString(),
                sessionId = sessionId,
            ),
        )
        _state.update { it.copy(sessionId = null, participants = emptyList()) }
    }

    suspend fun sendOffer(recipientId: String, sdp: String, generation: Long? = null) {
        sendWebRtc(SignalingMessageTypes.OFFER, recipientId, sdp, "offer", generation)
    }

    suspend fun sendAnswer(recipientId: String, sdp: String, generation: Long? = null) {
        sendWebRtc(SignalingMessageTypes.ANSWER, recipientId, sdp, "answer", generation)
    }

    suspend fun sendIceCandidate(
        recipientId: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?,
        generation: Long? = null,
    ) {
        val sessionId = _state.value.sessionId ?: return
        transport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.ICE_CANDIDATE,
                requestId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                recipientId = recipientId,
                payload = buildJsonObject {
                    put("candidate", JsonPrimitive(candidate))
                    sdpMid?.let { put("sdpMid", JsonPrimitive(it)) }
                    sdpMLineIndex?.let { put("sdpMLineIndex", JsonPrimitive(it)) }
                    generation?.let { put("generation", JsonPrimitive(it)) }
                },
            ),
        )
    }

    suspend fun ping() {
        val ts = System.currentTimeMillis()
        transport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.PING,
                requestId = UUID.randomUUID().toString(),
                payload = buildJsonObject { put("timestamp", JsonPrimitive(ts)) },
            ),
        )
    }

    private suspend fun sendWebRtc(
        type: String,
        recipientId: String,
        sdp: String,
        sdpType: String,
        generation: Long?,
    ) {
        val sessionId = _state.value.sessionId ?: return
        transport.send(
            SignalingEnvelope(
                type = type,
                requestId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                recipientId = recipientId,
                payload = buildJsonObject {
                    put("sdp", JsonPrimitive(sdp))
                    put("sdpType", JsonPrimitive(sdpType))
                    generation?.let { put("generation", JsonPrimitive(it)) }
                },
            ),
        )
    }

    private suspend fun waitForSessionId(requestId: String): Result<String> {
        val msg = withTimeoutOrNull(10_000) {
            incoming.first {
                it.requestId == requestId &&
                    (it.type == SignalingMessageTypes.SESSION_CREATED || it.type == SignalingMessageTypes.ERROR)
            }
        } ?: return Result.failure(IllegalStateException("timeout"))
        if (msg.type == SignalingMessageTypes.ERROR) {
            return Result.failure(IllegalStateException(msg.payload["message"]?.jsonPrimitive?.content))
        }
        val id = msg.payload["sessionId"]?.jsonPrimitive?.content
        if (id != null) {
            _state.update { it.copy(sessionId = id) }
            return Result.success(id)
        }
        return Result.failure(IllegalStateException("sessionId manquant"))
    }

    private suspend fun waitForJoin(requestId: String): Result<Unit> {
        val msg = withTimeoutOrNull(10_000) {
            incoming.first {
                it.requestId == requestId &&
                    (it.type == SignalingMessageTypes.SESSION_JOINED || it.type == SignalingMessageTypes.ERROR)
            }
        } ?: return Result.failure(IllegalStateException("timeout"))
        if (msg.type == SignalingMessageTypes.ERROR) {
            return Result.failure(IllegalStateException(msg.payload["message"]?.jsonPrimitive?.content))
        }
        return Result.success(Unit)
    }

    fun applyEnvelope(envelope: SignalingEnvelope) {
        when (envelope.type) {
            SignalingMessageTypes.AUTHENTICATED -> {
                val uid = envelope.payload["uid"]?.jsonPrimitive?.content
                _state.update {
                    it.copy(connectionState = ConnectionState.AUTHENTICATED, localUid = uid, lastError = null)
                }
            }
            SignalingMessageTypes.AUTHENTICATION_ERROR -> {
                val msg = envelope.payload["message"]?.jsonPrimitive?.content ?: "Auth error"
                _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, lastError = msg) }
            }
            SignalingMessageTypes.SESSION_CREATED, SignalingMessageTypes.SESSION_JOINED -> {
                val sessionId = envelope.payload["sessionId"]?.jsonPrimitive?.content
                val participants = envelope.payload["participants"]?.toString()
                _state.update {
                    it.copy(sessionId = sessionId, participants = parseParticipants(participants))
                }
            }
            SignalingMessageTypes.PARTICIPANT_JOINED -> {
                val id = envelope.payload["participantId"]?.jsonPrimitive?.content
                if (id != null) {
                    _state.update { s -> s.copy(participants = (s.participants + id).distinct()) }
                }
            }
            SignalingMessageTypes.PARTICIPANT_LEFT -> {
                val id = envelope.payload["participantId"]?.jsonPrimitive?.content
                if (id != null) {
                    _state.update { s -> s.copy(participants = s.participants.filterNot { it == id }) }
                }
            }
            SignalingMessageTypes.PONG -> {
                val ts = envelope.payload["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                if (ts != null) {
                    _state.update { it.copy(lastRttMs = System.currentTimeMillis() - ts) }
                }
            }
            SignalingMessageTypes.ERROR -> {
                val msg = envelope.payload["message"]?.jsonPrimitive?.content
                _state.update { it.copy(lastError = msg) }
            }
        }
    }

    private fun parseParticipants(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return Regex("\"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.toList()
    }
}
