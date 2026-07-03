package com.nblaisot.voxcrew.signaling

import com.nblaisot.voxcrew.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class SignalingClient(
    private val baseUrl: String,
    private val authRepository: AuthRepository,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(SignalingUiState())
    val state: StateFlow<SignalingUiState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<SignalingEnvelope>(extraBufferCapacity = 64)
    val incoming: SharedFlow<SignalingEnvelope> = _incoming.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var intentionalDisconnect = false
    private var reconnectAttempt = 0
    private val sendMutex = Mutex()

    fun connect() {
        intentionalDisconnect = false
        reconnectAttempt = 0
        openSocket()
    }

    fun disconnect() {
        intentionalDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
    }

    suspend fun createSession(name: String? = null): Result<String> {
        val requestId = UUID.randomUUID().toString()
        send(
            SignalingEnvelope(
                type = SignalingMessageTypes.CREATE_SESSION,
                requestId = requestId,
                payload = buildJsonObject {
                    name?.let { put("name", JsonPrimitive(it)) }
                },
            ),
        )
        return waitForSessionId(requestId)
    }

    suspend fun joinSession(sessionId: String): Result<Unit> {
        val requestId = UUID.randomUUID().toString()
        send(
            SignalingEnvelope(
                type = SignalingMessageTypes.JOIN_SESSION,
                requestId = requestId,
                payload = jsonPayload("sessionId" to sessionId),
            ),
        )
        return waitForJoin(requestId, sessionId)
    }

    suspend fun leaveSession() {
        val sessionId = _state.value.sessionId ?: return
        send(
            SignalingEnvelope(
                type = SignalingMessageTypes.LEAVE_SESSION,
                requestId = UUID.randomUUID().toString(),
                sessionId = sessionId,
            ),
        )
        _state.update { it.copy(sessionId = null, participants = emptyList()) }
    }

    suspend fun sendOffer(recipientId: String, sdp: String) {
        sendWebRtc(SignalingMessageTypes.OFFER, recipientId, sdp, "offer")
    }

    suspend fun sendAnswer(recipientId: String, sdp: String) {
        sendWebRtc(SignalingMessageTypes.ANSWER, recipientId, sdp, "answer")
    }

    suspend fun sendIceCandidate(recipientId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        val sessionId = _state.value.sessionId ?: return
        send(
            SignalingEnvelope(
                type = SignalingMessageTypes.ICE_CANDIDATE,
                requestId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                recipientId = recipientId,
                payload = buildJsonObject {
                    put("candidate", JsonPrimitive(candidate))
                    sdpMid?.let { put("sdpMid", JsonPrimitive(it)) }
                    sdpMLineIndex?.let { put("sdpMLineIndex", JsonPrimitive(it)) }
                },
            ),
        )
    }

    suspend fun ping() {
        val ts = System.currentTimeMillis()
        send(
            SignalingEnvelope(
                type = SignalingMessageTypes.PING,
                requestId = UUID.randomUUID().toString(),
                payload = buildJsonObject { put("timestamp", JsonPrimitive(ts)) },
            ),
        )
    }

    private suspend fun sendWebRtc(type: String, recipientId: String, sdp: String, sdpType: String) {
        val sessionId = _state.value.sessionId ?: return
        send(
            SignalingEnvelope(
                type = type,
                requestId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                recipientId = recipientId,
                payload = jsonPayload("sdp" to sdp, "sdpType" to sdpType),
            ),
        )
    }

    private suspend fun send(envelope: SignalingEnvelope) {
        sendMutex.withLock {
            val ws = webSocket ?: error("WebSocket non connecté")
            ws.send(signalingJson.encodeToString(SignalingEnvelope.serializer(), envelope))
        }
    }

    private fun openSocket() {
        _state.update { it.copy(connectionState = ConnectionState.CONNECTING, lastError = null) }
        val wsUrl = baseUrl.replace("https://", "wss://").replace("http://", "ws://").trimEnd('/') + "/ws"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { authenticate() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.update { it.copy(lastError = t.message) }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!intentionalDisconnect) scheduleReconnect()
            }
        })
    }

    private suspend fun authenticate() {
        val token = authRepository.getIdToken(forceRefresh = reconnectAttempt > 0).getOrElse { error ->
            _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, lastError = error.message) }
            return
        }
        send(
            SignalingEnvelope(
                type = SignalingMessageTypes.AUTHENTICATE,
                requestId = UUID.randomUUID().toString(),
                payload = jsonPayload("token" to token),
            ),
        )
    }

    private suspend fun handleMessage(text: String) {
        val envelope = signalingJson.decodeFromString(SignalingEnvelope.serializer(), text)
        when (envelope.type) {
            SignalingMessageTypes.AUTHENTICATED -> {
                val uid = envelope.payload["uid"]?.jsonPrimitive?.content
                _state.update {
                    it.copy(connectionState = ConnectionState.AUTHENTICATED, localUid = uid, lastError = null)
                }
                reconnectAttempt = 0
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
        _incoming.emit(envelope)
    }

    private fun scheduleReconnect() {
        if (intentionalDisconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _state.update { it.copy(connectionState = ConnectionState.RECONNECTING) }
            while (isActive && !intentionalDisconnect) {
                reconnectAttempt += 1
                val base = min(30_000L, 1_000L shl min(reconnectAttempt, 5))
                val jitter = Random.nextLong(0, 500)
                delay(base + jitter)
                openSocket()
                break
            }
        }
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
        return if (id != null) Result.success(id) else Result.failure(IllegalStateException("sessionId manquant"))
    }

    private suspend fun waitForJoin(requestId: String, sessionId: String): Result<Unit> {
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

    private fun parseParticipants(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return Regex("\"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.toList()
    }
}
