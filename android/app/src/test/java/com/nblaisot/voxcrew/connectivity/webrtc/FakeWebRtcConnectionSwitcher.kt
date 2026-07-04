package com.nblaisot.voxcrew.connectivity.webrtc

import com.nblaisot.voxcrew.audio.TransmissionPolicy
import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.webrtc.WebRtcDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class FakeManagedPeerConnection(
    override val generation: GenerationId,
    ready: Boolean = false,
) : ManagedPeerConnection {
    private val _diagnostics = MutableStateFlow(
        WebRtcDiagnostics(dataChannelOpen = ready, peerState = if (ready) com.nblaisot.voxcrew.webrtc.PeerState.CONNECTED else com.nblaisot.voxcrew.webrtc.PeerState.NEW),
    )
    override val diagnostics: StateFlow<WebRtcDiagnostics> = _diagnostics.asStateFlow()
    override var isMediaReady: Boolean = ready
        private set

    override var onIceCandidate: ((IceCandidate) -> Unit)? = null
    override var onOfferCreated: ((SessionDescription) -> Unit)? = null
    override var onAnswerCreated: ((SessionDescription) -> Unit)? = null

    var closed = false
    var promoted = false

    fun markReady() {
        isMediaReady = true
        _diagnostics.value = _diagnostics.value.copy(
            dataChannelOpen = true,
            peerState = com.nblaisot.voxcrew.webrtc.PeerState.CONNECTED,
            iceState = com.nblaisot.voxcrew.webrtc.IceTransportState.CONNECTED,
        )
    }

    override fun createOffer() = Unit
    override fun createAnswer() = Unit
    override fun setRemoteDescription(sdp: SessionDescription) = Unit
    override fun addIceCandidate(candidate: IceCandidate) = Unit
    override suspend fun createOfferAwait(iceRestart: Boolean): SessionDescription =
        SessionDescription(SessionDescription.Type.OFFER, "fake-offer")
    override suspend fun createAnswerAwait(): SessionDescription =
        SessionDescription(SessionDescription.Type.ANSWER, "fake-answer")
    override suspend fun setRemoteDescriptionAwait(sdp: SessionDescription) = Unit
    override fun muteIncomingAudio(muted: Boolean) = Unit
    override fun close() {
        closed = true
    }
}

class FakeWebRtcConnectionSwitcher : WebRtcConnectionSwitcher {
    private val connections = mutableMapOf<Long, FakeManagedPeerConnection>()
    private var active: FakeManagedPeerConnection? = null

    private val _activeGeneration = MutableStateFlow<GenerationId?>(null)
    override val activeGeneration: StateFlow<GenerationId?> = _activeGeneration.asStateFlow()

    private val _diagnostics = MutableStateFlow(WebRtcDiagnostics())
    override val diagnostics: StateFlow<WebRtcDiagnostics> = _diagnostics.asStateFlow()

    private val _lastSwitch = MutableStateFlow<SwitchEvent?>(null)
    override val lastSwitch: StateFlow<SwitchEvent?> = _lastSwitch.asStateFlow()

    private val _remoteAudioActive = MutableStateFlow(false)
    override val remoteAudioActive: StateFlow<Boolean> = _remoteAudioActive.asStateFlow()

    val promoteCalls = mutableListOf<GenerationId>()
    val retireCalls = mutableListOf<GenerationId>()

    override fun createConnection(
        generation: GenerationId,
        isInitiator: Boolean,
        useLanIce: Boolean,
    ): ManagedPeerConnection {
        val conn = FakeManagedPeerConnection(generation)
        connections[generation.value] = conn
        if (active == null) {
            active = conn
            _activeGeneration.value = generation
        }
        return conn
    }

    override suspend fun promote(candidate: ManagedPeerConnection, reason: String) {
        val fake = candidate as FakeManagedPeerConnection
        fake.promoted = true
        promoteCalls.add(fake.generation)
        active = fake
        _activeGeneration.value = fake.generation
        _lastSwitch.value = SwitchEvent(null, fake.generation, reason, System.currentTimeMillis())
    }

    override suspend fun retire(generation: GenerationId) {
        retireCalls.add(generation)
        connections.remove(generation.value)?.close()
        if (active?.generation == generation) active = null
    }

    override fun activeConnection(): ManagedPeerConnection? = active

    override fun connectionFor(generation: GenerationId): ManagedPeerConnection? =
        connections[generation.value]

    override fun attachTransmissionPolicy(policy: TransmissionPolicy) = Unit

    override fun closeAll() {
        connections.values.forEach { it.close() }
        connections.clear()
        active = null
        _activeGeneration.value = null
    }

    fun connection(gen: GenerationId): FakeManagedPeerConnection? = connections[gen.value]
}
