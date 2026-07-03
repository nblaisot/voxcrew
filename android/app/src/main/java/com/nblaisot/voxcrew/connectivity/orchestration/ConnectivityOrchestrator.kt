package com.nblaisot.voxcrew.connectivity.orchestration

import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.connectivity.model.TransportMode
import com.nblaisot.voxcrew.connectivity.state.ConnectivityDiagnostics
import com.nblaisot.voxcrew.connectivity.state.ConnectivityState
import com.nblaisot.voxcrew.connectivity.state.TransportPreference
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface ConnectivityOrchestrator {
    val state: StateFlow<ConnectivityState>
    val diagnostics: StateFlow<ConnectivityDiagnostics>
    val relayedSignaling: SharedFlow<SignalingEnvelope>

    suspend fun beginSession(descriptor: SessionDescriptor, preference: TransportPreference = TransportPreference.AUTO)
    suspend fun endSession()
    fun setTransportPreference(preference: TransportPreference)
    suspend fun relayWebRtc(envelope: SignalingEnvelope)
    suspend fun evaluateNow()
}
