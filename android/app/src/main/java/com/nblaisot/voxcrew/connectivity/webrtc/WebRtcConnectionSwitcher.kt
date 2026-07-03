package com.nblaisot.voxcrew.connectivity.webrtc

import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.webrtc.IceTransportState
import com.nblaisot.voxcrew.webrtc.PeerState
import com.nblaisot.voxcrew.webrtc.WebRtcDiagnostics
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

interface ManagedPeerConnection {
    val generation: GenerationId
    val diagnostics: StateFlow<WebRtcDiagnostics>
    val isMediaReady: Boolean

    var onIceCandidate: ((IceCandidate) -> Unit)?
    var onOfferCreated: ((SessionDescription) -> Unit)?
    var onAnswerCreated: ((SessionDescription) -> Unit)?

    fun createOffer()
    fun createAnswer()
    fun setRemoteDescription(sdp: SessionDescription)
    fun addIceCandidate(candidate: IceCandidate)
    fun muteIncomingAudio(muted: Boolean)
    fun close()
}

data class SwitchEvent(
    val fromGeneration: GenerationId?,
    val toGeneration: GenerationId,
    val reason: String,
    val atMs: Long,
)

interface WebRtcConnectionSwitcher {
    val activeGeneration: StateFlow<GenerationId?>
    val diagnostics: StateFlow<WebRtcDiagnostics>
    val lastSwitch: StateFlow<SwitchEvent?>

    fun createConnection(generation: GenerationId, isInitiator: Boolean, useLanIce: Boolean): ManagedPeerConnection
    suspend fun promote(candidate: ManagedPeerConnection, reason: String)
    suspend fun retire(generation: GenerationId)
    fun activeConnection(): ManagedPeerConnection?
    fun connectionFor(generation: GenerationId): ManagedPeerConnection?
    fun attachTransmissionPolicy(policy: com.nblaisot.voxcrew.audio.TransmissionPolicy)
    fun closeAll()
}

fun WebRtcDiagnostics.isReadyForPromotion(): Boolean =
    peerState == PeerState.CONNECTED &&
        (iceState == IceTransportState.CONNECTED || iceState == IceTransportState.COMPLETED) &&
        dataChannelOpen
