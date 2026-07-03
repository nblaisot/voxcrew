package com.nblaisot.voxcrew.connectivity.transport

import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.signaling.ConnectionState
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.signaling.signalingJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

class LocalLanSignalingTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
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

    fun configureEndpoint(host: String, port: Int) {
        _state.update { it.copy(endpoint = "http://$host:$port") }
    }

    override suspend fun connect(session: SessionDescriptor, generation: GenerationId) {
        val base = _state.value.endpoint ?: error("Local endpoint not configured")
        activeGeneration = generation
        pendingSession = session
        _state.update { it.copy(generation = generation, connectionState = ConnectionState.CONNECTING) }
        val wsUrl = base.replace("https://", "ws://").replace("http://", "ws://").trimEnd('/') + "/ws"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
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
                _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, lastError = t.message) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
            }
        })
    }

    private suspend fun handleMessage(text: String) {
        val envelope = signalingJson.decodeFromString(SignalingEnvelope.serializer(), text)
        when (envelope.type) {
            SignalingMessageTypes.AUTHENTICATED ->
                _state.update { it.copy(connectionState = ConnectionState.AUTHENTICATED) }
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
        val ws = webSocket ?: error("Not connected")
        ws.send(signalingJson.encodeToString(SignalingEnvelope.serializer(), envelope))
    }

    override suspend fun disconnect(generation: GenerationId) {
        if (activeGeneration?.value != generation.value) return
        webSocket?.close(1000, "disconnect")
        webSocket = null
        activeGeneration = null
        pendingSession = null
        _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED, generation = null) }
    }
}
