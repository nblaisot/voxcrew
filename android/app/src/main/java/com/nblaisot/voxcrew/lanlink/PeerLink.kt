package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Transport-agnostic core of the intercom link with one peer: owns the
 * outgoing sequence space and the [SendBuffer] of frames the peer has not
 * yet acknowledged, so "packets accumulate on the sender until the
 * connection is available again" holds across a switch between transports
 * (LAN TCP, hole-punched UDP, cloud relay) exactly as it already holds
 * across a plain reconnect on the same transport — one sequence space
 * survives every path switch.
 *
 * A [FrameTransport] establishes physical connectivity and performs its own
 * Hello/resume handshake (framing differs enough between a TCP stream, a UDP
 * datagram and a WebSocket relay message that this is left to each
 * transport), then hands control to this class via [onHandshakeComplete].
 * From then on [PeerLink] deduplicates/orders inbound audio, drives
 * ACK/PING on a shared timer, and exposes [rttMs] / [backlogMs] for the UI.
 */
class PeerLink(private val scope: CoroutineScope) {
    sealed class LinkState {
        data object Idle : LinkState()
        data class Connecting(val peerUid: String) : LinkState()
        data class Connected(val peerUid: String, val via: String) : LinkState()
        data class Disconnected(val peerUid: String) : LinkState()
    }

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incomingAudio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingAudio: SharedFlow<ByteArray> = _incomingAudio.asSharedFlow()

    private val _rttMs = MutableStateFlow<Long?>(null)
    val rttMs: StateFlow<Long?> = _rttMs.asStateFlow()

    /** Accumulated audio time the peer has not confirmed receiving yet (frames in [sendBuffer] × frame duration). */
    private val _backlogMs = MutableStateFlow(0L)
    val backlogMs: StateFlow<Long> = _backlogMs.asStateFlow()

    private var currentPeerUid: String? = null
    private var activeTransport: FrameTransport? = null
    private var healthLoopJob: Job? = null

    private val sendBuffer = SendBuffer()
    @Volatile private var outSeq = 0L
    @Volatile private var lastContiguousInSeq = -1L
    @Volatile private var lastActivityMs = System.currentTimeMillis()
    @Volatile private var lastPingSentMs = 0L

    val selectedPeerUid: String? get() = currentPeerUid

    fun lastContiguousInSeq(): Long = lastContiguousInSeq

    /** Age of the oldest frame the peer has not acknowledged yet, 0 if none pending. */
    fun oldestUnackedAgeMs(): Long {
        val oldest = sendBuffer.oldestEnqueuedAtMs() ?: return 0L
        return System.currentTimeMillis() - oldest
    }

    /**
     * All frames not yet acknowledged by the peer, oldest first. Used by transports
     * (e.g. UDP) that must actively retransmit rather than relying on the transport
     * itself for reliability, the way TCP does.
     */
    fun unacknowledgedFrames(): List<SendBuffer.Entry> = sendBuffer.replayFrom(-1)

    /** Resets all protocol state for a brand new peer conversation (not a transport switch). */
    fun resetFor(peerUid: String) {
        if (currentPeerUid == peerUid) return
        activeTransport?.stop()
        activeTransport = null
        currentPeerUid = peerUid
        sendBuffer.clear()
        outSeq = 0
        lastContiguousInSeq = -1
        lastActivityMs = System.currentTimeMillis()
        _backlogMs.value = 0
        _rttMs.value = null
        _state.value = LinkState.Idle
        ensureHealthLoop()
    }

    fun clear() {
        healthLoopJob?.cancel()
        healthLoopJob = null
        activeTransport?.stop()
        activeTransport = null
        currentPeerUid = null
        sendBuffer.clear()
        outSeq = 0
        lastContiguousInSeq = -1
        _backlogMs.value = 0
        _rttMs.value = null
        _state.value = LinkState.Idle
    }

    /** Marks the link as actively trying (e.g. a transport is dialing/punching), for status display. */
    fun markConnecting(peerUid: String) {
        if (currentPeerUid != peerUid) return
        if (_state.value !is LinkState.Connected) _state.value = LinkState.Connecting(peerUid)
    }

    /** Buffers immediately; flushes over the active transport right away if there is one. */
    fun send(payload: ByteArray) {
        val seq = outSeq++
        sendBuffer.add(seq, payload)
        updateBacklog()
        activeTransport?.sendFrame(LanFrame.Audio(seq, payload))
    }

    /** Called by a transport once its own Hello/resume exchange with [peerUid] has succeeded. */
    fun onHandshakeComplete(transport: FrameTransport, peerUid: String, peerAnnouncedLastContiguousSeq: Long) {
        if (currentPeerUid != peerUid) return
        if (activeTransport !== transport) {
            activeTransport?.stop()
        }
        activeTransport = transport
        lastActivityMs = System.currentTimeMillis()
        sendBuffer.trimTo(peerAnnouncedLastContiguousSeq)
        updateBacklog()
        _state.value = LinkState.Connected(peerUid, transport.label)
        sendBuffer.replayFrom(peerAnnouncedLastContiguousSeq).forEach {
            transport.sendFrame(LanFrame.Audio(it.seq, it.data))
        }
    }

    /** Called by the active transport for every frame it decodes other than its own Hello. */
    fun onFrameReceived(transport: FrameTransport, frame: LanFrame) {
        if (activeTransport !== transport) return
        lastActivityMs = System.currentTimeMillis()
        when (frame) {
            is LanFrame.Audio -> {
                if (frame.seq > lastContiguousInSeq) {
                    lastContiguousInSeq = frame.seq
                    _incomingAudio.tryEmit(frame.payload)
                }
            }
            is LanFrame.Ack -> {
                sendBuffer.trimTo(frame.lastContiguousSeq)
                updateBacklog()
            }
            is LanFrame.Ping -> transport.sendFrame(LanFrame.Pong(frame.timestampMs))
            is LanFrame.Pong -> _rttMs.value = System.currentTimeMillis() - frame.timestampMs
            is LanFrame.Hello -> Unit
        }
    }

    /** Called by a transport once it has dropped its connection to [peerUid]. */
    fun onDisconnected(transport: FrameTransport, peerUid: String) {
        if (activeTransport !== transport) return
        activeTransport = null
        _rttMs.value = null
        _state.value = LinkState.Disconnected(peerUid)
    }

    private fun ensureHealthLoop() {
        if (healthLoopJob?.isActive == true) return
        healthLoopJob = scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                delay(ACK_INTERVAL_MS)
                val transport = activeTransport ?: continue
                transport.sendFrame(LanFrame.Ack(lastContiguousInSeq))
                val now = System.currentTimeMillis()
                if (now - lastPingSentMs > PING_INTERVAL_MS) {
                    lastPingSentMs = now
                    transport.sendFrame(LanFrame.Ping(now))
                }
                if (now - lastActivityMs > PEER_TIMEOUT_MS) {
                    lastActivityMs = now
                    transport.dropAndRetry()
                }
            }
        }
    }

    private fun updateBacklog() {
        _backlogMs.value = sendBuffer.size().toLong() * AudioCapture.FRAME_MS
    }

    companion object {
        private const val ACK_INTERVAL_MS = 250L
        private const val PING_INTERVAL_MS = 2_000L
        private const val PEER_TIMEOUT_MS = 12_000L

        /** Threshold used by the path manager to consider a connected local link "degraded". */
        const val DEGRADED_RTT_MS = 2_000L
        const val DEGRADED_UNACKED_AGE_MS = 1_000L
    }
}
