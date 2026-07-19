package com.nblaisot.voxcrew.signaling

import android.content.Context
import com.nblaisot.voxcrew.BuildConfig
import com.nblaisot.voxcrew.R
import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.connectivity.transport.CloudRunSignalingTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class SignalingClient(
    context: Context,
    baseUrl: String,
    authRepository: AuthRepository,
    private val transport: CloudRunSignalingTransport = CloudRunSignalingTransport(baseUrl, authRepository),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(SignalingUiState())
    val state: StateFlow<SignalingUiState> = _state.asStateFlow()

    private val _presenceMembers = MutableStateFlow<List<PresenceMember>>(emptyList())
    val presenceMembers: StateFlow<List<PresenceMember>> = _presenceMembers.asStateFlow()

    private val _peerOffline = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val peerOffline: SharedFlow<String> = _peerOffline.asSharedFlow()

    val incoming: SharedFlow<SignalingEnvelope> = transport.incomingMessages

    private val _reauthenticated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reauthenticated: SharedFlow<Unit> = _reauthenticated.asSharedFlow()

    private var rejoinContext: RejoinContext? = null
    private var previousTransportState = ConnectionState.DISCONNECTED

    val isCloudConfigured: Boolean = !BuildConfig.SIGNALING_BASE_URL.contains("PLACEHOLDER", ignoreCase = true)

    init {
        scope.launch {
            transport.incomingMessages.collect { applyEnvelope(it) }
        }
        scope.launch {
            transport.state.collect { ts ->
                val becameAuthenticated =
                    previousTransportState != ConnectionState.AUTHENTICATED &&
                        ts.connectionState == ConnectionState.AUTHENTICATED
                previousTransportState = ts.connectionState
                _state.update {
                    it.copy(
                        connectionState = ts.connectionState,
                        lastError = mapFriendlyError(ts.lastError, code = null),
                    )
                }
                if (becameAuthenticated && rejoinContext != null) {
                    scope.launch { runCatching { restoreSessionAfterReconnect() } }
                }
            }
        }
    }

    fun setRejoinContext(sessionId: String, email: String, transportHint: String) {
        rejoinContext = RejoinContext(sessionId, email, transportHint)
    }

    fun clearRejoinContext() {
        rejoinContext = null
    }

    private suspend fun restoreSessionAfterReconnect() {
        val ctx = rejoinContext ?: return
        sendPresenceRegister(ctx.email, ctx.transportHint)
        joinOrCreatePairSession(ctx.sessionId).getOrElse { return }
        _reauthenticated.emit(Unit)
    }

    fun connect() = transport.connectCloud()

    fun retryConnection() = transport.connectCloud()

    fun disconnect() = transport.disconnectAll()

    suspend fun awaitAuthenticated(timeoutMs: Long = 30_000) {
        if (state.value.connectionState == ConnectionState.AUTHENTICATED) return
        withTimeoutOrNull(timeoutMs) {
            state.first { it.connectionState == ConnectionState.AUTHENTICATED }
        } ?: error("Signaling non connecté")
    }

    suspend fun sendPresenceRegister(email: String, transportHint: String) {
        transport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.PRESENCE_REGISTER,
                requestId = UUID.randomUUID().toString(),
                payload = buildJsonObject {
                    val normalized = email.trim()
                    if (normalized.isNotEmpty()) put("email", JsonPrimitive(normalized))
                    put("transportHint", JsonPrimitive(transportHint))
                },
            ),
        )
    }

    suspend fun sendPresenceHeartbeat(transportHint: String) {
        transport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.PRESENCE_HEARTBEAT,
                requestId = UUID.randomUUID().toString(),
                payload = buildJsonObject {
                    put("transportHint", JsonPrimitive(transportHint))
                },
            ),
        )
    }

    suspend fun joinOrCreatePairSession(sessionId: String): Result<Unit> {
        val join = joinSession(sessionId)
        if (join.isSuccess) return join
        val requestId = UUID.randomUUID().toString()
        transport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.CREATE_SESSION,
                requestId = requestId,
                payload = buildJsonObject {
                    put("sessionId", JsonPrimitive(sessionId))
                    put("name", JsonPrimitive("pair"))
                },
            ),
        )
        return waitForSessionCreated(requestId)
    }

    suspend fun createSession(name: String? = null, sessionId: String? = null): Result<String> {
        val requestId = UUID.randomUUID().toString()
        transport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.CREATE_SESSION,
                requestId = requestId,
                payload = buildJsonObject {
                    name?.let { put("name", JsonPrimitive(it)) }
                    sessionId?.let { put("sessionId", JsonPrimitive(it)) }
                },
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

    private suspend fun waitForSessionCreated(requestId: String): Result<Unit> {
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
        }
        return Result.success(Unit)
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
                val code = envelope.payload["code"]?.jsonPrimitive?.content
                val msg = envelope.payload["message"]?.jsonPrimitive?.content ?: "Auth error"
                _state.update {
                    it.copy(
                        connectionState = ConnectionState.DISCONNECTED,
                        lastError = mapFriendlyError(msg, code),
                    )
                }
            }
            SignalingMessageTypes.SESSION_CREATED, SignalingMessageTypes.SESSION_JOINED -> {
                val sessionId = envelope.payload["sessionId"]?.jsonPrimitive?.content
                val participants = parseParticipants(envelope.payload["participants"])
                _state.update {
                    it.copy(sessionId = sessionId, participants = participants)
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
            SignalingMessageTypes.PRESENCE_SNAPSHOT -> {
                applyPresenceSnapshot(envelope)
                _state.update { it.copy(lastError = null) }
            }
            SignalingMessageTypes.PRESENCE_UPDATED -> applyPresenceUpdated(envelope)
            SignalingMessageTypes.PRESENCE_OFFLINE -> applyPresenceOffline(envelope)
            SignalingMessageTypes.PONG -> {
                val ts = envelope.payload["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                if (ts != null) {
                    _state.update { it.copy(lastRttMs = System.currentTimeMillis() - ts) }
                }
            }
            SignalingMessageTypes.ERROR -> {
                val code = envelope.payload["code"]?.jsonPrimitive?.content
                val msg = envelope.payload["message"]?.jsonPrimitive?.content
                // SESSION_NOT_FOUND is an expected outcome of the join-or-create
                // flow and "Already authenticated" is a harmless race artifact.
                val benign = code == "SESSION_NOT_FOUND" ||
                    msg.equals("Already authenticated", ignoreCase = true)
                if (!benign) {
                    _state.update { it.copy(lastError = mapFriendlyError(msg, code)) }
                }
            }
        }
    }

    private fun applyPresenceSnapshot(envelope: SignalingEnvelope) {
        val members = envelope.payload["members"]?.jsonArray ?: return
        _presenceMembers.value = members.mapNotNull { parsePresenceMember(it.jsonObject) }
    }

    private fun applyPresenceUpdated(envelope: SignalingEnvelope) {
        val member = parsePresenceMember(envelope.payload) ?: return
        _presenceMembers.update { current ->
            val without = current.filterNot { it.uid == member.uid }
            without + member
        }
    }

    private fun applyPresenceOffline(envelope: SignalingEnvelope) {
        val uid = envelope.payload["uid"]?.jsonPrimitive?.content ?: return
        _presenceMembers.update { current ->
            current.map {
                if (it.uid == uid) it.copy(online = false, lastSeenMs = envelope.payload["lastSeenMs"]?.jsonPrimitive?.content?.toLongOrNull()) else it
            }
        }
        _peerOffline.tryEmit(uid)
    }

    private fun parsePresenceMember(obj: JsonObject): PresenceMember? {
        val uid = obj["uid"]?.jsonPrimitive?.content ?: return null
        val email = obj["email"]?.jsonPrimitive?.content ?: uid
        return PresenceMember(
            uid = uid,
            email = email,
            transportHint = obj["transportHint"]?.jsonPrimitive?.content ?: "cloud",
            online = obj["online"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            lastSeenMs = obj["lastSeenMs"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
    }

    private fun parseParticipants(element: JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { it.jsonPrimitive.content }
    }

    private fun mapFriendlyError(raw: String?, code: String? = null): String? {
        if (raw.isNullOrBlank() && code.isNullOrBlank()) return null
        val text = raw.orEmpty()
        val normalizedCode = code?.uppercase().orEmpty()
        if (!isCloudConfigured) return appContext.getString(R.string.signaling_cloud_unavailable)
        if (text.contains("Unable to resolve host", ignoreCase = true)) {
            return appContext.getString(R.string.signaling_cloud_unavailable)
        }
        if (text.contains("failed to connect", ignoreCase = true) || text.contains("Failed to connect", ignoreCase = true)) {
            return appContext.getString(R.string.signaling_cloud_unavailable)
        }
        when {
            normalizedCode == "NOT_ALLOWED" || text.contains("not allowed", ignoreCase = true) ->
                return appContext.getString(R.string.signaling_not_allowed)
            normalizedCode == "TOKEN_EXPIRED" || text.contains("expired", ignoreCase = true) ->
                return appContext.getString(R.string.signaling_session_expired)
            normalizedCode == "TOKEN_INVALID" || text.contains("Invalid token", ignoreCase = true) ->
                return appContext.getString(R.string.signaling_auth_rejected)
            normalizedCode == "TIMEOUT" || text.contains("timeout", ignoreCase = true) ->
                return appContext.getString(R.string.signaling_timeout)
            normalizedCode == "INVALID_MESSAGE" || text.contains("Invalid message envelope", ignoreCase = true) ->
                return appContext.getString(R.string.signaling_protocol_error)
            text.contains("Non connecté", ignoreCase = true) || text.contains("Jeton indisponible", ignoreCase = true) ||
                text.contains("Not connected", ignoreCase = true) || text.contains("Token unavailable", ignoreCase = true) ->
                return appContext.getString(R.string.signaling_not_connected)
            text.contains("WebSocket non connecté", ignoreCase = true) ||
                text.contains("WebSocket not connected", ignoreCase = true) ->
                return appContext.getString(R.string.signaling_disconnected)
        }
        return text.ifBlank { appContext.getString(R.string.signaling_connection_failed) }
    }
}

private data class RejoinContext(
    val sessionId: String,
    val email: String,
    val transportHint: String,
)
