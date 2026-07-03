package com.nblaisot.voxcrew.ui.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nblaisot.voxcrew.audio.OpenMicTransmissionPolicy
import com.nblaisot.voxcrew.audio.PushToTalkTransmissionPolicy
import com.nblaisot.voxcrew.audio.TransmissionMode
import com.nblaisot.voxcrew.audio.TransmissionPolicy
import com.nblaisot.voxcrew.service.SessionForegroundService
import com.nblaisot.voxcrew.signaling.SignalingClient
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.webrtc.IceTransportState
import com.nblaisot.voxcrew.webrtc.PeerState
import com.nblaisot.voxcrew.webrtc.WebRtcSessionManager
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
)

class SessionViewModel(
    private val appContext: Context,
    private val signalingClient: SignalingClient,
    private val webRtc: WebRtcSessionManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var openMicPolicy = OpenMicTransmissionPolicy()
    private var pttPolicy = PushToTalkTransmissionPolicy()
    private var activePolicy: TransmissionPolicy = openMicPolicy
    private var remotePeerId: String? = null
    private var isInitiator = false
    private var sessionStarted = false

    private var policyWatchJob: Job? = null

    init {
        watchTransmissionPolicy(activePolicy)
        viewModelScope.launch {
            combine(signalingClient.state, webRtc.diagnostics) { sig, diag ->
                _uiState.value.copy(
                    participants = sig.participants,
                    localUid = sig.localUid,
                    peerState = diag.peerState,
                    iceState = diag.iceState,
                    selectedCandidateType = diag.selectedCandidateType,
                    dataChannelRttMs = diag.lastDataChannelRttMs,
                    transmissionMode = activePolicy.mode,
                )
            }.collect { _uiState.value = it }
        }
        viewModelScope.launch {
            signalingClient.incoming.collect { handleSignaling(it) }
        }
    }

    fun start(sessionId: String) {
        if (sessionStarted) return
        sessionStarted = true
        SessionForegroundService.start(appContext, connected = false)
        setupWebRtcCallbacks()
        val participants = signalingClient.state.value.participants
        val local = signalingClient.state.value.localUid
        remotePeerId = participants.firstOrNull { it != local }
        isInitiator = local != null && participants.minOrNull() == local
        webRtc.createPeerConnection(isInitiator)
        webRtc.enableAudioTrack(activePolicy)
        if (isInitiator && remotePeerId != null) {
            webRtc.createOffer()
        }
        SessionForegroundService.start(appContext, connected = true)
        log("Session démarrée initiator=$isInitiator peer=$remotePeerId")
    }

    fun onMicPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(micPermissionGranted = granted) }
    }

    fun useOpenMic() {
        activePolicy = openMicPolicy
        webRtc.attachTransmissionPolicy(activePolicy)
        watchTransmissionPolicy(activePolicy)
        _uiState.update { it.copy(transmissionMode = TransmissionMode.OPEN_MIC) }
    }

    fun usePushToTalk() {
        activePolicy = pttPolicy
        pttPolicy.cancel()
        webRtc.attachTransmissionPolicy(activePolicy)
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

    fun sendDataChannelPing() = webRtc.sendDataChannelPing()
    fun refreshStats() = webRtc.refreshStats()
    fun toggleDiagnostics() = _uiState.update { it.copy(showDiagnostics = !it.showDiagnostics) }

    fun leave() {
        viewModelScope.launch {
            signalingClient.leaveSession()
        }
        webRtc.close()
        SessionForegroundService.stop(appContext)
        sessionStarted = false
    }

    private fun setupWebRtcCallbacks() {
        webRtc.onIceCandidate = { candidate ->
            val peer = remotePeerId
            if (peer != null) {
                viewModelScope.launch {
                    signalingClient.sendIceCandidate(
                        peer,
                        candidate.sdp,
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                    )
                }
            }
        }
        webRtc.onOfferCreated = { sdp ->
            val peer = remotePeerId
            if (peer != null) {
                viewModelScope.launch { signalingClient.sendOffer(peer, sdp.description) }
            }
        }
        webRtc.onAnswerCreated = { sdp ->
            val peer = remotePeerId
            if (peer != null) {
                viewModelScope.launch { signalingClient.sendAnswer(peer, sdp.description) }
            }
        }
    }

    private fun handleSignaling(envelope: SignalingEnvelope) {
        val local = _uiState.value.localUid ?: signalingClient.state.value.localUid
        when (envelope.type) {
            SignalingMessageTypes.PARTICIPANT_JOINED -> {
                val id = envelope.payload["participantId"]?.jsonPrimitive?.content
                if (id != null && id != local) {
                    remotePeerId = id
                    if (isInitiator) webRtc.createOffer()
                }
            }
            SignalingMessageTypes.OFFER -> {
                val sdp = envelope.payload["sdp"]?.jsonPrimitive?.content ?: return
                envelope.senderId?.let { remotePeerId = it }
                webRtc.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp))
                webRtc.createAnswer()
            }
            SignalingMessageTypes.ANSWER -> {
                val sdp = envelope.payload["sdp"]?.jsonPrimitive?.content ?: return
                webRtc.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            SignalingMessageTypes.ICE_CANDIDATE -> {
                val c = envelope.payload["candidate"]?.jsonPrimitive?.content ?: return
                val mid = envelope.payload["sdpMid"]?.jsonPrimitive?.content
                val idx = envelope.payload["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull()
                webRtc.addIceCandidate(IceCandidate(mid, idx ?: 0, c))
            }
        }
    }

    private fun log(message: String) {
        _uiState.update { it.copy(diagnosticsLog = it.diagnosticsLog + message) }
    }

    override fun onCleared() {
        webRtc.close()
        super.onCleared()
    }
}
