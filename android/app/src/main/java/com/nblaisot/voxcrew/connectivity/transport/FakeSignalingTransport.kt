package com.nblaisot.voxcrew.connectivity.transport

import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.signaling.ConnectionState
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.signaling.jsonPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSignalingTransport(
    override val kind: SignalingTransportKind = SignalingTransportKind.CLOUD,
) : SignalingTransport {
    private val _state = MutableStateFlow(SignalingTransportState(kind = kind))
    override val state: StateFlow<SignalingTransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<SignalingEnvelope>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<SignalingEnvelope> = _incoming.asSharedFlow()

    val sent = mutableListOf<SignalingEnvelope>()
    var connected = false

    override suspend fun connect(session: SessionDescriptor, generation: GenerationId) {
        connected = true
        _state.value = SignalingTransportState(
            kind = kind,
            connectionState = ConnectionState.AUTHENTICATED,
            generation = generation,
        )
        _incoming.emit(
            SignalingEnvelope(
                type = SignalingMessageTypes.AUTHENTICATED,
                requestId = "fake-auth",
                senderId = session.participantId,
                payload = jsonPayload("uid" to session.participantId),
            ),
        )
    }

    override suspend fun send(envelope: SignalingEnvelope) {
        sent.add(envelope)
    }

    override suspend fun disconnect(generation: GenerationId) {
        connected = false
        _state.value = SignalingTransportState(kind = kind, connectionState = ConnectionState.DISCONNECTED)
    }

    suspend fun emit(envelope: SignalingEnvelope) {
        _incoming.emit(envelope)
    }
}
