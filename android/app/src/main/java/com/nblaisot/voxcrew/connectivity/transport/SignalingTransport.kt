package com.nblaisot.voxcrew.connectivity.transport

import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.signaling.ConnectionState
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SignalingTransportKind {
    CLOUD,
    LOCAL_LAN,
}

data class SignalingTransportState(
    val kind: SignalingTransportKind,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val generation: GenerationId? = null,
    val lastError: String? = null,
    val endpoint: String? = null,
)

interface SignalingTransport {
    val kind: SignalingTransportKind
    /** When true, the transport stays connected for app-wide signaling; orchestrator must not reconnect/disconnect it. */
    val sharesIntercomSignaling: Boolean get() = false
    val state: StateFlow<SignalingTransportState>
    val incomingMessages: Flow<SignalingEnvelope>

    suspend fun connect(session: SessionDescriptor, generation: GenerationId)
    suspend fun send(envelope: SignalingEnvelope)
    suspend fun disconnect(generation: GenerationId)
}
