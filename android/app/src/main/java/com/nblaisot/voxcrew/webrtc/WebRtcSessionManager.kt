package com.nblaisot.voxcrew.webrtc

import android.content.Context
import com.nblaisot.voxcrew.audio.TransmissionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.Charset

enum class PeerState {
    NEW,
    CONNECTING,
    CONNECTED,
    FAILED,
    CLOSED,
}

enum class IceTransportState {
    NEW,
    CHECKING,
    CONNECTED,
    COMPLETED,
    FAILED,
    DISCONNECTED,
}

data class WebRtcDiagnostics(
    val peerState: PeerState = PeerState.NEW,
    val iceState: IceTransportState = IceTransportState.NEW,
    val selectedCandidateType: String? = null,
    val localAudioEnabled: Boolean = false,
    val dataChannelOpen: Boolean = false,
    val lastDataChannelRttMs: Long? = null,
    val audioCodec: String? = null,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
)

class WebRtcSessionManager(
    private val appContext: Context,
    private val iceServerConfig: IceServerConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _diagnostics = MutableStateFlow(WebRtcDiagnostics())
    val diagnostics: StateFlow<WebRtcDiagnostics> = _diagnostics.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var policyCollectJob: kotlinx.coroutines.Job? = null

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onOfferCreated: ((SessionDescription) -> Unit)? = null
    var onAnswerCreated: ((SessionDescription) -> Unit)? = null

    private var lastPingSentAt: Long = 0

    fun initialize() {
        if (factory != null) return
        val initOpts = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOpts)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun createPeerConnection(isInitiator: Boolean) {
        initialize()
        close()
        val pc = factory!!.createPeerConnection(
            iceServerConfig.toPeerIceServers(),
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
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit
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
                    if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                        refreshStats()
                    }
                }
            },
        ) ?: error("PeerConnection creation failed")
        peerConnection = pc

        if (isInitiator) {
            val init = pc.createDataChannel("voxcrew-diag", DataChannel.Init())
            setupDataChannel(init)
        }
    }

    fun attachTransmissionPolicy(policy: TransmissionPolicy) {
        policyCollectJob?.cancel()
        policyCollectJob = policy.shouldTransmit
            .onEach { transmit ->
                localAudioTrack?.setEnabled(transmit)
                _diagnostics.update { it.copy(localAudioEnabled = transmit) }
            }
            .launchIn(scope)
    }

    fun enableAudioTrack(policy: TransmissionPolicy) {
        val f = factory ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = f.createAudioSource(constraints)
        localAudioTrack = f.createAudioTrack("voxcrew_audio", audioSource).apply {
            setEnabled(policy.shouldTransmit.value)
        }
        peerConnection?.addTrack(localAudioTrack, listOf("voxcrew_stream"))
        attachTransmissionPolicy(policy)
    }

    fun createOffer() {
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                onOfferCreated?.invoke(desc)
            }
        }, MediaConstraints())
    }

    fun createAnswer() {
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                onAnswerCreated?.invoke(desc)
            }
        }, MediaConstraints())
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun sendDataChannelPing() {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        lastPingSentAt = System.currentTimeMillis()
        val payload = """{"type":"ping","t":$lastPingSentAt}""".toByteArray()
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), false))
    }

    fun refreshStats() {
        peerConnection?.getStats { report ->
            var sent = 0L
            var received = 0L
            var bytesOut = 0L
            var bytesIn = 0L
            var codec: String? = null
            var candidateType: String? = null
            for (stat in report.statsMap.values) {
                when (stat.type) {
                    "outbound-rtp" -> if (stat.members["kind"] == "audio") {
                        sent = (stat.members["packetsSent"] as? Number)?.toLong() ?: sent
                        bytesOut = (stat.members["bytesSent"] as? Number)?.toLong() ?: bytesOut
                    }
                    "inbound-rtp" -> if (stat.members["kind"] == "audio") {
                        received = (stat.members["packetsReceived"] as? Number)?.toLong() ?: received
                        bytesIn = (stat.members["bytesReceived"] as? Number)?.toLong() ?: bytesIn
                        codec = stat.members["mimeType"] as? String ?: codec
                    }
                    "candidate-pair" -> if (stat.members["selected"] == true) {
                        candidateType = stat.members["candidateType"] as? String ?: candidateType
                    }
                }
            }
            _diagnostics.update {
                it.copy(
                    packetsSent = sent,
                    packetsReceived = received,
                    bytesSent = bytesOut,
                    bytesReceived = bytesIn,
                    audioCodec = codec,
                    selectedCandidateType = candidateType,
                )
            }
        }
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

    fun close() {
        policyCollectJob?.cancel()
        dataChannel?.close()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        dataChannel = null
        localAudioTrack = null
        audioSource = null
        peerConnection = null
        _diagnostics.value = WebRtcDiagnostics()
    }

    fun dispose() {
        close()
        factory?.dispose()
        factory = null
        PeerConnectionFactory.stopInternalTracingCapture()
        PeerConnectionFactory.shutdownInternalTracer()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
