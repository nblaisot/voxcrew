package com.nblaisot.voxcrew.connectivity.transport

import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.signaling.ConnectionState
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.signaling.signalingJson
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

class LocalLanSignalingTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : SignalingTransport {
    override val kind = SignalingTransportKind.LOCAL_LAN

    private val _state = MutableStateFlow(SignalingTransportState(kind = kind))
    override val state: StateFlow<SignalingTransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<SignalingEnvelope>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<SignalingEnvelope> = _incoming.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var activeGeneration: GenerationId? = null
    private var pendingSession: SessionDescriptor? = null
    private var reconnectJob: Job? = null
    private var intentionalDisconnect = false
    private var reconnectAttempt = 0
    private var socketOpen = false
    private val sendMutex = Mutex()

    fun configureEndpoint(host: String, port: Int) {
        _state.update { it.copy(endpoint = "http://$host:$port") }
    }

    override suspend fun connect(session: SessionDescriptor, generation: GenerationId) {
        activeGeneration = generation
        pendingSession = session
        intentionalDisconnect = false
        reconnectAttempt = 0
        _state.update { it.copy(generation = generation, connectionState = ConnectionState.CONNECTING) }
        openSocket()
    }

    private fun openSocket() {
        val base = _state.value.endpoint ?: return
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "reconnect")
        webSocket = null
        socketOpen = false
        val wsUrl = base.replace("https://", "ws://").replace("http://", "ws://").trimEnd('/') + "/ws"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketOpen = true
                val s = pendingSession ?: return
                val secret = s.sessionSecret ?: return
                val envelope = SignalingEnvelope(
                    type = SignalingMessageTypes.AUTHENTICATE,
                    requestId = UUID.randomUUID().toString(),
                    payload = buildJsonObject {
                        put("authKind", JsonPrimitive("local"))
                        put("sessionId", JsonPrimitive(s.sessionId))
                        put("localToken", JsonPrimitive(secret))
                        put("participantId", JsonPrimitive(s.participantId))
                    },
                )
                webSocket.send(signalingJson.encodeToString(SignalingEnvelope.serializer(), envelope))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketOpen = false
                _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, lastError = t.message) }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketOpen = false
                _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
                if (!intentionalDisconnect) scheduleReconnect()
            }
        })
    }

    private suspend fun handleMessage(text: String) {
        val envelope = signalingJson.decodeFromString(SignalingEnvelope.serializer(), text)
        when (envelope.type) {
            SignalingMessageTypes.AUTHENTICATED -> {
                reconnectAttempt = 0
                _state.update { it.copy(connectionState = ConnectionState.AUTHENTICATED, lastError = null) }
            }
            SignalingMessageTypes.AUTHENTICATION_ERROR ->
                _state.update {
                    it.copy(
                        connectionState = ConnectionState.DISCONNECTED,
                        lastError = envelope.payload["message"]?.jsonPrimitive?.content,
                    )
                }
        }
        _incoming.emit(envelope)
    }

    override suspend fun send(envelope: SignalingEnvelope) {
        sendMutex.withLock {
            val ws = awaitWritableSocket()
            val ok = ws.send(signalingJson.encodeToString(SignalingEnvelope.serializer(), envelope))
            if (!ok) error("LAN WebSocket send failed")
        }
    }

    private suspend fun awaitWritableSocket(): WebSocket {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val ws = webSocket
            if (ws != null && socketOpen) return ws
            delay(50)
        }
        error("LAN WebSocket non connecté")
    }

    private fun scheduleReconnect() {
        if (intentionalDisconnect || pendingSession == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _state.update { it.copy(connectionState = ConnectionState.RECONNECTING) }
            if (isActive && !intentionalDisconnect) {
                reconnectAttempt += 1
                val base = min(30_000L, 1_000L shl min(reconnectAttempt, 5))
                val jitter = Random.nextLong(0, 500)
                delay(base + jitter)
                openSocket()
            }
        }
    }

    override suspend fun disconnect(generation: GenerationId) {
        if (activeGeneration?.value != generation.value) return
        intentionalDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "disconnect")
        webSocket = null
        socketOpen = false
        activeGeneration = null
        pendingSession = null
        _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, generation = null) }
    }
}
