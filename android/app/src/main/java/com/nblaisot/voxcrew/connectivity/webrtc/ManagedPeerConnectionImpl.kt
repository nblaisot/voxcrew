package com.nblaisot.voxcrew.connectivity.webrtc

import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.webrtc.IceServerConfig
import com.nblaisot.voxcrew.webrtc.IceTransportState
import com.nblaisot.voxcrew.webrtc.PeerState
import com.nblaisot.voxcrew.webrtc.WebRtcDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.Charset

class ManagedPeerConnectionImpl(
    override val generation: GenerationId,
    private val factoryFacade: PeerConnectionFactoryFacade,
    private val cloudIce: IceServerConfig,
    private val lanIce: IceServerConfig,
    private val useLanIce: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ManagedPeerConnection {
    private val _diagnostics = MutableStateFlow(WebRtcDiagnostics())
    override val diagnostics: StateFlow<WebRtcDiagnostics> = _diagnostics.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var remoteAudioReceivers = mutableListOf<RtpReceiver>()
    private var lastPingSentAt = 0L
    private var audioAttached = false

    override var onIceCandidate: ((IceCandidate) -> Unit)? = null
    override var onOfferCreated: ((SessionDescription) -> Unit)? = null
    override var onAnswerCreated: ((SessionDescription) -> Unit)? = null

    override val isMediaReady: Boolean
        get() = _diagnostics.value.isReadyForPromotion()

    init {
        val ice = if (useLanIce) lanIce else cloudIce
        val factory = factoryFacade.getOrCreate()
        peerConnection = factory.createPeerConnection(
            ice.toPeerIceServers(),
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    _diagnostics.update {
                        it.copy(
                            iceState = when (state) {
                                PeerConnection.IceConnectionState.CHECKING -> IceTransportState.CHECKING
                                PeerConnection.IceConnectionState.CONNECTED -> IceTransportState.CONNECTED
                                PeerConnection.IceConnectionState.COMPLETED -> IceTransportState.COMPLETED
                                PeerConnection.IceConnectionState.FAILED -> IceTransportState.FAILED
                                PeerConnection.IceConnectionState.DISCONNECTED -> IceTransportState.DISCONNECTED
                                else -> IceTransportState.NEW
                            },
                        )
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { onIceCandidate?.invoke(it) }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onDataChannel(channel: DataChannel?) {
                    channel?.let { setupDataChannel(it) }
                }
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                    receiver?.let { remoteAudioReceivers.add(it) }
                }
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    _diagnostics.update {
                        it.copy(
                            peerState = when (newState) {
                                PeerConnection.PeerConnectionState.CONNECTING -> PeerState.CONNECTING
                                PeerConnection.PeerConnectionState.CONNECTED -> PeerState.CONNECTED
                                PeerConnection.PeerConnectionState.FAILED -> PeerState.FAILED
                                PeerConnection.PeerConnectionState.CLOSED -> PeerState.CLOSED
                                else -> PeerState.NEW
                            },
                        )
                    }
                }
            },
        )
    }

    fun ensureAudioTrack(enabled: Boolean) {
        if (audioAttached) return
        val factory = factoryFacade.getOrCreate()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("voxcrew_audio_${generation.value}", audioSource).apply {
            setEnabled(enabled)
        }
        peerConnection?.addTrack(localAudioTrack, listOf("voxcrew_stream"))
        audioAttached = true
    }

    fun createDataChannelIfInitiator(isInitiator: Boolean) {
        if (!isInitiator) return
        val init = peerConnection?.createDataChannel("voxcrew-diag", DataChannel.Init())
        init?.let { setupDataChannel(it) }
    }

    override fun createOffer() {
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                onOfferCreated?.invoke(desc)
            }
        }, MediaConstraints())
    }

    override fun createAnswer() {
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                onAnswerCreated?.invoke(desc)
            }
        }, MediaConstraints())
    }

    override fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    override fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    override fun muteIncomingAudio(muted: Boolean) {
        remoteAudioReceivers.forEach { receiver ->
            (receiver.track() as? AudioTrack)?.setEnabled(!muted)
        }
    }

    fun setLocalAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        _diagnostics.update { it.copy(localAudioEnabled = enabled) }
    }

    fun sendDataChannelPing() {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        lastPingSentAt = System.currentTimeMillis()
        val payload = """{"type":"ping","t":$lastPingSentAt}""".toByteArray()
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), false))
    }

    private fun setupDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                _diagnostics.update { it.copy(dataChannelOpen = channel.state() == DataChannel.State.OPEN) }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charset.forName("UTF-8"))
                if (text.contains("ping")) {
                    val pong = """{"type":"pong","t":${System.currentTimeMillis()}}""".toByteArray()
                    channel.send(DataChannel.Buffer(ByteBuffer.wrap(pong), false))
                } else if (text.contains("pong") && lastPingSentAt > 0) {
                    _diagnostics.update { it.copy(lastDataChannelRttMs = System.currentTimeMillis() - lastPingSentAt) }
                }
            }
        })
    }

    override fun close() {
        dataChannel?.close()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        dataChannel = null
        localAudioTrack = null
        audioSource = null
        peerConnection = null
        remoteAudioReceivers.clear()
        _diagnostics.value = WebRtcDiagnostics()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
