package com.nblaisot.voxcrew.connectivity.transport

import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.lanlink.BinaryRelayChannel
import com.nblaisot.voxcrew.signaling.ConnectionState
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.signaling.jsonPayload
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
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

class CloudRunSignalingTransport(
    private val baseUrl: String,
    private val authRepository: AuthRepository,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SignalingTransport, BinaryRelayChannel {
    override val kind = SignalingTransportKind.CLOUD
    override val sharesIntercomSignaling: Boolean = true

    private val _state = MutableStateFlow(
        SignalingTransportState(kind = SignalingTransportKind.CLOUD, endpoint = baseUrl),
    )
    override val state: StateFlow<SignalingTransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<SignalingEnvelope>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<SignalingEnvelope> = _incoming.asSharedFlow()

    /**
     * Opaque binary WebSocket frames (the cloud relay path for [com.nblaisot.voxcrew.lanlink.PeerLink]).
     * The backend forwards these uid-to-uid without parsing or storing them; see
     * `backend/src/ws/handler.ts` `onBinaryMessage`.
     */
    private val _incomingBinary = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingBinary: SharedFlow<ByteArray> = _incomingBinary.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var intentionalDisconnect = false
    private var reconnectAttempt = 0
    private var socketOpen = false
    private var activeGeneration: GenerationId? = null
    private val sendMutex = Mutex()

    fun connectCloud() {
        intentionalDisconnect = false
        reconnectAttempt = 0
        openSocket()
    }

    override fun connect() = connectCloud()

    override suspend fun connect(session: SessionDescriptor, generation: GenerationId) {
        activeGeneration = generation
        _state.update { it.copy(generation = generation, connectionState = ConnectionState.CONNECTING) }
        connectCloud()
    }

    override suspend fun disconnect(generation: GenerationId) {
        if (activeGeneration?.value != generation.value) return
        intentionalDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "disconnect")
        webSocket = null
        socketOpen = false
        activeGeneration = null
        _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, generation = null) }
    }

    fun disconnectAll() {
        intentionalDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        socketOpen = false
        activeGeneration = null
        _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, generation = null) }
    }

    override suspend fun send(envelope: SignalingEnvelope) {
        sendMutex.withLock {
            val ws = awaitWritableSocket()
            ws.send(signalingJson.encodeToString(SignalingEnvelope.serializer(), envelope))
        }
    }

    /**
     * Best-effort binary send for the relay path: no queueing/blocking, since audio frames
     * are already buffered upstream in [com.nblaisot.voxcrew.lanlink.SendBuffer] and replayed
     * once the socket (or a better path) is available again.
     */
    override fun sendBinary(bytes: ByteArray) {
        val ws = webSocket
        if (ws == null || !socketOpen) return
        ws.send(Buffer().write(bytes).readByteString())
    }

    private suspend fun awaitWritableSocket(): WebSocket {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val ws = webSocket
            if (ws != null && socketOpen) return ws
            delay(50)
        }
        error("WebSocket non connecté")
    }

    private fun openSocket() {
        if (socketOpen && webSocket != null && _state.value.connectionState == ConnectionState.AUTHENTICATED) {
            return
        }
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "reconnect")
        webSocket = null
        socketOpen = false
        _state.update { it.copy(connectionState = ConnectionState.CONNECTING, lastError = null) }
        val wsUrl = baseUrl.replace("https://", "wss://").replace("http://", "ws://").trimEnd('/') + "/ws"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (webSocket !== ws) return
                socketOpen = true
                scope.launch { authenticate(ws) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (webSocket !== ws) return
                scope.launch { handleMessage(text) }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (webSocket !== ws) return
                _incomingBinary.tryEmit(bytes.toByteArray())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                socketOpen = false
                _state.update { it.copy(lastError = t.message) }
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                socketOpen = false
                if (!intentionalDisconnect) scheduleReconnect()
            }
        })
    }

    private suspend fun authenticate(ws: WebSocket) {
        val token = authRepository.getIdToken(forceRefresh = reconnectAttempt > 0).getOrElse { error ->
            _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, lastError = error.message) }
            scheduleReconnect()
            return
        }
        val envelope = SignalingEnvelope(
            type = SignalingMessageTypes.AUTHENTICATE,
            requestId = UUID.randomUUID().toString(),
            payload = jsonPayload("token" to token),
        )
        // Send on the exact socket that just opened; going through send() could
        // deliver the token to a newer socket and double-authenticate it.
        ws.send(signalingJson.encodeToString(SignalingEnvelope.serializer(), envelope))
    }

    private suspend fun handleMessage(text: String) {
        val envelope = signalingJson.decodeFromString(SignalingEnvelope.serializer(), text)
        when (envelope.type) {
            SignalingMessageTypes.AUTHENTICATED -> {
                _state.update {
                    it.copy(connectionState = ConnectionState.AUTHENTICATED, lastError = null)
                }
                reconnectAttempt = 0
            }
            SignalingMessageTypes.AUTHENTICATION_ERROR -> {
                val msg = envelope.payload["message"]?.jsonPrimitive?.content ?: "Auth error"
                _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, lastError = msg) }
                scheduleReconnect()
            }
            SignalingMessageTypes.ERROR -> {
                val code = envelope.payload["code"]?.jsonPrimitive?.content
                val msg = envelope.payload["message"]?.jsonPrimitive?.content
                val benign = code == "SESSION_NOT_FOUND" ||
                    msg.equals("Already authenticated", ignoreCase = true)
                if (!benign) {
                    _state.update { it.copy(lastError = msg) }
                }
            }
        }
        _incoming.emit(envelope)
    }

    private fun scheduleReconnect() {
        if (intentionalDisconnect) return
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
}
