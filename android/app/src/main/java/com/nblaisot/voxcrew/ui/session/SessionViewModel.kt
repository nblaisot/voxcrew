package com.nblaisot.voxcrew.ui.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nblaisot.voxcrew.audio.OpenMicTransmissionPolicy
import com.nblaisot.voxcrew.audio.PushToTalkTransmissionPolicy
import com.nblaisot.voxcrew.audio.TransmissionMode
import com.nblaisot.voxcrew.audio.TransmissionPolicy
import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.connectivity.model.TransportMode
import com.nblaisot.voxcrew.connectivity.orchestration.ConnectivityOrchestrator
import com.nblaisot.voxcrew.connectivity.state.ConnectivityState
import com.nblaisot.voxcrew.connectivity.state.TransportPreference
import com.nblaisot.voxcrew.connectivity.webrtc.ManagedPeerConnection
import com.nblaisot.voxcrew.connectivity.webrtc.WebRtcConnectionSwitcher
import com.nblaisot.voxcrew.service.SessionForegroundService
import com.nblaisot.voxcrew.signaling.SignalingClient
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.webrtc.IceTransportState
import com.nblaisot.voxcrew.webrtc.PeerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

data class SessionUiState(
    val participants: List<String> = emptyList(),
    val localUid: String? = null,
    val peerState: PeerState = PeerState.NEW,
    val iceState: IceTransportState = IceTransportState.NEW,
    val selectedCandidateType: String? = null,
    val transmissionMode: TransmissionMode = TransmissionMode.OPEN_MIC,
    val isTransmitting: Boolean = false,
    val dataChannelRttMs: Long? = null,
    val showDiagnostics: Boolean = false,
    val diagnosticsLog: List<String> = emptyList(),
    val micPermissionGranted: Boolean = false,
    val transportLabel: String = "—",
    val connectivityStateLabel: String = "Idle",
    val activeGeneration: Long? = null,
    val candidateGeneration: Long? = null,
    val lastSwitchReason: String? = null,
    val transportPreference: TransportPreference = TransportPreference.AUTO,
    val localAddress: String? = null,
)

class SessionViewModel(
    private val appContext: Context,
    private val signalingClient: SignalingClient,
    private val orchestrator: ConnectivityOrchestrator,
    private val connectionSwitcher: WebRtcConnectionSwitcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var openMicPolicy = OpenMicTransmissionPolicy()
    private var pttPolicy = PushToTalkTransmissionPolicy()
    private var activePolicy: TransmissionPolicy = openMicPolicy
    private var remotePeerId: String? = null
    private var isInitiator = false
    private var sessionStarted = false
    private var activeGeneration: GenerationId? = null

    private var policyWatchJob: Job? = null

    init {
        watchTransmissionPolicy(activePolicy)
        connectionSwitcher.attachTransmissionPolicy(activePolicy)
        viewModelScope.launch {
            combine(signalingClient.state, connectionSwitcher.diagnostics, orchestrator.diagnostics) { sig, webrtc, conn ->
                _uiState.value.copy(
                    participants = sig.participants,
                    localUid = sig.localUid,
                    peerState = webrtc.peerState,
                    iceState = webrtc.iceState,
                    selectedCandidateType = webrtc.selectedCandidateType,
                    dataChannelRttMs = webrtc.lastDataChannelRttMs,
                    transmissionMode = activePolicy.mode,
                    transportLabel = transportLabel(conn.activeTransport),
                    connectivityStateLabel = conn.connectivityState::class.simpleName ?: "—",
                    activeGeneration = conn.activeGeneration,
                    candidateGeneration = conn.candidateGeneration,
                    lastSwitchReason = conn.lastSwitchReason,
                    localAddress = conn.localAddress,
                )
            }.collect { _uiState.value = it }
        }
        viewModelScope.launch {
            signalingClient.incoming.collect { handleSignaling(it) }
        }
        viewModelScope.launch {
            orchestrator.relayedSignaling.collect { handleSignaling(it) }
        }
    }

    fun start(sessionId: String, isLocalHost: Boolean = false) {
        if (sessionStarted) return
        sessionStarted = true
        SessionForegroundService.start(appContext, transportLabel = "Connexion…")
        val localUid = signalingClient.state.value.localUid ?: return
        val descriptor = SessionDescriptor(
            sessionId = sessionId,
            participantId = localUid,
            hostParticipantId = signalingClient.state.value.participants.minOrNull(),
            isLocalHost = isLocalHost,
        )
        viewModelScope.launch {
            orchestrator.beginSession(descriptor, _uiState.value.transportPreference)
        }
        setupWebRtcCallbacks()
        val participants = signalingClient.state.value.participants
        val local = signalingClient.state.value.localUid
        remotePeerId = participants.firstOrNull { it != local }
        isInitiator = local != null && (descriptor.hostParticipantId == local || participants.minOrNull() == local)
        val gen = connectionSwitcher.activeGeneration.value ?: GenerationId.next().also {
            connectionSwitcher.createConnection(it, isInitiator, useLanIce = isLocalHost)
            activeGeneration = it
        }
        activeGeneration = gen
        wireConnectionCallbacks(connectionSwitcher.activeConnection())
        if (isInitiator && remotePeerId != null) {
            connectionSwitcher.activeConnection()?.createOffer()
        }
        SessionForegroundService.start(appContext, transportLabel = transportLabel(TransportMode.LOCAL_LAN))
        log("Session démarrée initiator=$isInitiator peer=$remotePeerId gen=${gen.value}")
    }

    fun setTransportPreference(preference: TransportPreference) {
        _uiState.update { it.copy(transportPreference = preference) }
        orchestrator.setTransportPreference(preference)
        viewModelScope.launch { orchestrator.evaluateNow() }
    }

    fun onMicPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(micPermissionGranted = granted) }
    }

    fun useOpenMic() {
        activePolicy = openMicPolicy
        connectionSwitcher.attachTransmissionPolicy(activePolicy)
        watchTransmissionPolicy(activePolicy)
        _uiState.update { it.copy(transmissionMode = TransmissionMode.OPEN_MIC) }
    }

    fun usePushToTalk() {
        activePolicy = pttPolicy
        pttPolicy.cancel()
        connectionSwitcher.attachTransmissionPolicy(activePolicy)
        watchTransmissionPolicy(activePolicy)
        _uiState.update { it.copy(transmissionMode = TransmissionMode.PUSH_TO_TALK) }
    }

    private fun watchTransmissionPolicy(policy: TransmissionPolicy) {
        policyWatchJob?.cancel()
        policyWatchJob = viewModelScope.launch {
            policy.shouldTransmit.collect { tx ->
                _uiState.update { it.copy(isTransmitting = tx) }
            }
        }
    }

    fun pttPress() = pttPolicy.onPress()
    fun pttRelease() = pttPolicy.onRelease()

    fun sendDataChannelPing() {
        (connectionSwitcher.activeConnection() as? com.nblaisot.voxcrew.connectivity.webrtc.ManagedPeerConnectionImpl)
            ?.sendDataChannelPing()
    }

    fun refreshStats() = Unit
    fun toggleDiagnostics() = _uiState.update { it.copy(showDiagnostics = !it.showDiagnostics) }

    fun leave() {
        viewModelScope.launch {
            signalingClient.leaveSession()
            orchestrator.endSession()
        }
        connectionSwitcher.closeAll()
        SessionForegroundService.stop(appContext)
        sessionStarted = false
    }

    private fun setupWebRtcCallbacks() {
        viewModelScope.launch {
            connectionSwitcher.activeGeneration.collect { gen ->
                activeGeneration = gen
                wireConnectionCallbacks(connectionSwitcher.activeConnection())
            }
        }
    }

    private fun wireConnectionCallbacks(conn: ManagedPeerConnection?) {
        conn ?: return
        val gen = conn.generation
        conn.onIceCandidate = { candidate ->
            val peer = remotePeerId
            if (peer != null && !gen.isObsolete(connectionSwitcher.activeGeneration.value)) {
                viewModelScope.launch {
                    signalingClient.sendIceCandidate(peer, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex, gen.value)
                }
            }
        }
        conn.onOfferCreated = { sdp ->
            val peer = remotePeerId
            if (peer != null && !gen.isObsolete(connectionSwitcher.activeGeneration.value)) {
                viewModelScope.launch { signalingClient.sendOffer(peer, sdp.description, gen.value) }
            }
        }
        conn.onAnswerCreated = { sdp ->
            val peer = remotePeerId
            if (peer != null && !gen.isObsolete(connectionSwitcher.activeGeneration.value)) {
                viewModelScope.launch { signalingClient.sendAnswer(peer, sdp.description, gen.value) }
            }
        }
    }

    private fun handleSignaling(envelope: SignalingEnvelope) {
        val local = _uiState.value.localUid ?: signalingClient.state.value.localUid
        val envelopeGen = envelope.payload["generation"]?.jsonPrimitive?.content?.toLongOrNull()
        val activeGen = connectionSwitcher.activeGeneration.value?.value
        if (envelopeGen != null && activeGen != null && envelopeGen < activeGen) {
            log("obsolete_generation_event_ignored gen=$envelopeGen active=$activeGen")
            return
        }
        val conn = connectionSwitcher.activeConnection() ?: return
        when (envelope.type) {
            SignalingMessageTypes.PARTICIPANT_JOINED -> {
                val id = envelope.payload["participantId"]?.jsonPrimitive?.content
                if (id != null && id != local) {
                    remotePeerId = id
                    if (isInitiator) conn.createOffer()
                }
            }
            SignalingMessageTypes.OFFER -> {
                val sdp = envelope.payload["sdp"]?.jsonPrimitive?.content ?: return
                envelope.senderId?.let { remotePeerId = it }
                conn.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp))
                conn.createAnswer()
            }
            SignalingMessageTypes.ANSWER -> {
                val sdp = envelope.payload["sdp"]?.jsonPrimitive?.content ?: return
                conn.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            SignalingMessageTypes.ICE_CANDIDATE -> {
                val c = envelope.payload["candidate"]?.jsonPrimitive?.content ?: return
                val mid = envelope.payload["sdpMid"]?.jsonPrimitive?.content
                val idx = envelope.payload["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull()
                conn.addIceCandidate(IceCandidate(mid, idx ?: 0, c))
            }
        }
    }

    private fun transportLabel(mode: TransportMode): String = when (mode) {
        TransportMode.LOCAL_LAN -> "Local"
        TransportMode.CLOUD_DIRECT -> "Internet direct"
        TransportMode.CLOUD_RELAY -> "Internet relayé"
        TransportMode.NONE -> "Déconnecté"
    }

    private fun log(message: String) {
        _uiState.update { it.copy(diagnosticsLog = it.diagnosticsLog + message) }
    }

    override fun onCleared() {
        connectionSwitcher.closeAll()
        super.onCleared()
    }
}
