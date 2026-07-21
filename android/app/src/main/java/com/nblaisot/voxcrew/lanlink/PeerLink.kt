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
import java.util.TreeMap
import kotlin.coroutines.CoroutineContext

sealed interface IncomingMediaEvent {
    data class Audio(val payload: ByteArray) : IncomingMediaEvent
    data class Activity(val active: Boolean) : IncomingMediaEvent
}

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
class PeerLink(
    private val scope: CoroutineScope,
    private val healthDispatcher: CoroutineContext = Dispatchers.IO,
) {
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
    private val _incomingMedia = MutableSharedFlow<IncomingMediaEvent>(extraBufferCapacity = 256)
    val incomingMedia: SharedFlow<IncomingMediaEvent> = _incomingMedia.asSharedFlow()

    private val _rttMs = MutableStateFlow<Long?>(null)
    val rttMs: StateFlow<Long?> = _rttMs.asStateFlow()

    /** Accumulated audio time the peer has not confirmed receiving yet (frames in [sendBuffer] × frame duration). */
    private val _backlogMs = MutableStateFlow(0L)
    val backlogMs: StateFlow<Long> = _backlogMs.asStateFlow()

    private val _bufferExpired = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val bufferExpired: SharedFlow<Int> = _bufferExpired.asSharedFlow()

    private var currentPeerUid: String? = null
    private var activeTransport: FrameTransport? = null
    private var healthLoopJob: Job? = null

    private val sendBuffer = SendBuffer()
    @Volatile private var outSeq = 0L
    @Volatile private var lastContiguousInSeq = -1L
    /** Highest sequence the peer has confirmed (Hello resume or Ack). Drives [maybeSendSkip]. */
    @Volatile private var peerAckedSeq = -1L
    private val pendingInbound = TreeMap<Long, LanFrame>()
    @Volatile private var lastActivityMs = System.currentTimeMillis()
    @Volatile private var lastPingSentMs = 0L
    @Volatile private var awaitingPongSinceMs: Long? = null

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
        stopHealthLoop()
        currentPeerUid = peerUid
        sendBuffer.clear()
        outSeq = 0
        lastContiguousInSeq = -1
        peerAckedSeq = -1
        pendingInbound.clear()
        lastActivityMs = System.currentTimeMillis()
        awaitingPongSinceMs = null
        lastPingSentMs = 0L
        _backlogMs.value = 0
        _rttMs.value = null
        _state.value = LinkState.Idle
    }

    fun clear() {
        stopHealthLoop()
        activeTransport?.stop()
        activeTransport = null
        currentPeerUid = null
        sendBuffer.clear()
        outSeq = 0
        lastContiguousInSeq = -1
        peerAckedSeq = -1
        pendingInbound.clear()
        awaitingPongSinceMs = null
        lastPingSentMs = 0L
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
    @Synchronized
    fun send(payload: ByteArray) {
        expireStaleFrames()
        val seq = outSeq++
        sendBuffer.add(seq, payload)
        updateBacklog()
        activeTransport?.let { transport ->
            maybeSendSkip(transport)
            transport.sendFrame(LanFrame.Audio(seq, payload))
        }
    }

    @Synchronized
    fun sendMediaActivity(active: Boolean) {
        expireStaleFrames()
        val seq = outSeq++
        val kind = if (active) SendBuffer.Kind.MEDIA_ACTIVE else SendBuffer.Kind.MEDIA_INACTIVE
        sendBuffer.add(seq, ByteArray(0), kind = kind)
        activeTransport?.let { transport ->
            maybeSendSkip(transport)
            transport.sendFrame(LanFrame.MediaActivity(seq, active))
        }
    }

    /** Called by a transport once its own Hello/resume exchange with [peerUid] has succeeded. */
    @Synchronized
    fun onHandshakeComplete(transport: FrameTransport, peerUid: String, peerAnnouncedLastContiguousSeq: Long) {
        if (currentPeerUid != peerUid) return
        if (activeTransport !== transport) {
            activeTransport?.stop()
        }
        activeTransport = transport
        lastActivityMs = System.currentTimeMillis()
        awaitingPongSinceMs = null
        lastPingSentMs = 0L
        peerAckedSeq = peerAnnouncedLastContiguousSeq
        sendBuffer.trimTo(peerAnnouncedLastContiguousSeq)
        expireStaleFrames()
        updateBacklog()
        _state.value = LinkState.Connected(peerUid, transport.label)
        // Expiry/eviction while apart may have created a hole after the peer's cursor:
        // declare it before replaying so the receiver's contiguity can advance.
        maybeSendSkip(transport)
        sendBuffer.replayFrom(peerAnnouncedLastContiguousSeq).forEach {
            transport.sendFrame(it.toFrame())
        }
        ensureHealthLoop()
    }

    /** Called by the active transport for every frame it decodes other than its own Hello. */
    fun onFrameReceived(transport: FrameTransport, frame: LanFrame) {
        if (activeTransport !== transport) return
        lastActivityMs = System.currentTimeMillis()
        when (frame) {
            is LanFrame.Audio -> acceptSequenced(frame.seq, frame)
            is LanFrame.MediaActivity -> acceptSequenced(frame.seq, frame)
            is LanFrame.Skip -> acceptSkip(frame.untilSeq)
            is LanFrame.Ack -> {
                if (frame.lastContiguousSeq > peerAckedSeq) peerAckedSeq = frame.lastContiguousSeq
                sendBuffer.trimTo(frame.lastContiguousSeq)
                updateBacklog()
            }
            is LanFrame.Ping -> transport.sendFrame(LanFrame.Pong(frame.timestampMs))
            is LanFrame.Pong -> {
                // Ignore pongs from other ping sources (e.g. UDP keepalive) or delayed replies.
                if (frame.timestampMs != lastPingSentMs) return
                awaitingPongSinceMs = null
                val rtt = System.currentTimeMillis() - frame.timestampMs
                if (rtt in 0..PEER_TIMEOUT_MS) {
                    _rttMs.value = rtt
                }
            }
            is LanFrame.Hello -> Unit
        }
    }

    /** Called by a transport once it has dropped its connection to [peerUid]. */
    fun onDisconnected(transport: FrameTransport, peerUid: String) {
        if (activeTransport !== transport) return
        activeTransport = null
        stopHealthLoop()
        awaitingPongSinceMs = null
        _rttMs.value = null
        _state.value = LinkState.Disconnected(peerUid)
    }

    /**
     * Peer stopped responding or left the LAN. Clears the active transport without
     * resetting sequence state so a later reconnect can still resume the buffer.
     * UDP/Relay [FrameTransport.dropAndRetry] does not call [onDisconnected] itself
     * (unlike LAN TCP session close), so this is invoked from the health loop and
     * when a LAN beacon peer disappears.
     */
    fun markUnreachable() {
        val transport = activeTransport
        val uid = currentPeerUid
        if (transport != null && uid != null) {
            transport.dropAndRetry()
            onDisconnected(transport, uid)
            return
        }
        if (uid != null && _state.value is LinkState.Connected) {
            _rttMs.value = null
            _state.value = LinkState.Disconnected(uid)
        }
    }

    /** Testable liveness check used by the health loop. Returns true if the link should stay up. */
    internal fun evaluateLiveness(nowMs: Long): Boolean {
        if (activeTransport == null) return true
        // Frame activity (ACK/media/hello) is proof of life. Ping/Pong is RTT-only.
        if (awaitingPongSinceMs != null && nowMs - (awaitingPongSinceMs ?: 0L) > PONG_TIMEOUT_MS) {
            awaitingPongSinceMs = null
        }
        if (nowMs - lastActivityMs > PEER_TIMEOUT_MS) return false
        return true
    }

    internal fun markPingSentForTest(nowMs: Long) {
        lastPingSentMs = nowMs
        awaitingPongSinceMs = nowMs
    }

    @Synchronized
    internal fun expireStaleFramesForTest(nowMs: Long) {
        val dropped = sendBuffer.expireOlderThan(SendBuffer.DEFAULT_MAX_AGE_MS, nowMs)
        if (dropped > 0) {
            updateBacklog()
            _bufferExpired.tryEmit(dropped)
        }
    }

    /**
     * ACK/ping loop runs only while a transport is attached (started on handshake,
     * stopped on detach). Buffer expiry happens at event points ([send],
     * [onHandshakeComplete]) so nothing polls per peer while disconnected.
     */
    @Synchronized
    private fun ensureHealthLoop() {
        if (healthLoopJob?.isActive == true) return
        healthLoopJob = scope.launch(healthDispatcher) { runHealthLoop() }
    }

    private suspend fun runHealthLoop() {
        while (currentCoroutineContext().isActive) {
            delay(ACK_INTERVAL_MS)
            val transport = activeTransport
            if (transport == null) {
                // Exit only after re-checking under the monitor: a handshake racing this
                // window either sees an inactive job (and restarts one) or is seen here.
                val shouldExit = synchronized(this@PeerLink) {
                    if (activeTransport == null) {
                        healthLoopJob = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldExit) return
                continue
            }
            synchronized(this@PeerLink) {
                expireStaleFrames()
                maybeSendSkip(transport)
            }
            transport.sendFrame(LanFrame.Ack(lastContiguousInSeq))
            val now = System.currentTimeMillis()
            if (now - lastPingSentMs > PING_INTERVAL_MS) {
                lastPingSentMs = now
                awaitingPongSinceMs = now
                transport.sendFrame(LanFrame.Ping(now))
            }
            if (!evaluateLiveness(now)) {
                lastActivityMs = now
                awaitingPongSinceMs = null
                markUnreachable()
            }
        }
    }

    @Synchronized
    private fun stopHealthLoop() {
        healthLoopJob?.cancel()
        healthLoopJob = null
    }

    /** Caller must hold the [PeerLink] monitor (or be a @Synchronized member). */
    private fun expireStaleFrames() {
        val dropped = sendBuffer.expireOlderThan(SendBuffer.DEFAULT_MAX_AGE_MS)
        if (dropped > 0) {
            updateBacklog()
            _bufferExpired.tryEmit(dropped)
        }
    }

    private fun updateBacklog() {
        _backlogMs.value = sendBuffer.audioFrameCount().toLong() * AudioCapture.FRAME_MS
    }

    @Synchronized
    private fun acceptSequenced(seq: Long, frame: LanFrame) {
        if (seq <= lastContiguousInSeq || pendingInbound.containsKey(seq)) return
        if (pendingInbound.size >= MAX_PENDING_INBOUND_FRAMES) return
        pendingInbound[seq] = frame
        drainContiguousLocked()
    }

    /**
     * Sender declared that everything up to [untilSeq] no longer exists: fast-forward
     * the contiguity cursor and deliver whatever buffered frames become contiguous.
     */
    @Synchronized
    private fun acceptSkip(untilSeq: Long) {
        if (untilSeq <= lastContiguousInSeq) return
        lastContiguousInSeq = untilSeq
        pendingInbound.headMap(untilSeq, true).clear()
        drainContiguousLocked()
    }

    /**
     * Declares a sequence hole to the peer when expiry/eviction removed frames the
     * peer has not acknowledged. No-op while the buffered frames are contiguous with
     * the peer's cursor. Caller must hold the [PeerLink] monitor.
     */
    private fun maybeSendSkip(transport: FrameTransport) {
        val nextAvailableSeq = sendBuffer.firstSeq() ?: outSeq
        if (nextAvailableSeq > peerAckedSeq + 1) {
            transport.sendFrame(LanFrame.Skip(nextAvailableSeq - 1))
        }
    }

    private fun drainContiguousLocked() {
        while (true) {
            val nextSeq = lastContiguousInSeq + 1
            val next = pendingInbound.remove(nextSeq) ?: break
            lastContiguousInSeq = nextSeq
            when (next) {
                is LanFrame.Audio -> {
                    _incomingAudio.tryEmit(next.payload)
                    _incomingMedia.tryEmit(IncomingMediaEvent.Audio(next.payload))
                }
                is LanFrame.MediaActivity ->
                    _incomingMedia.tryEmit(IncomingMediaEvent.Activity(next.active))
                else -> Unit
            }
        }
    }

    companion object {
        private const val ACK_INTERVAL_MS = 250L
        private const val PING_INTERVAL_MS = 2_000L
        private const val PONG_TIMEOUT_MS = 3_000L
        private const val PEER_TIMEOUT_MS = 6_000L

        /** Threshold used by the path manager to consider a connected local link "degraded". */
        const val DEGRADED_RTT_MS = 2_000L
        const val DEGRADED_UNACKED_AGE_MS = 1_000L
        private const val MAX_PENDING_INBOUND_FRAMES = 512
    }
}
