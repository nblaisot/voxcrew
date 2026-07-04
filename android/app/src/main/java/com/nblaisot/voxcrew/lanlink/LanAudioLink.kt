package com.nblaisot.voxcrew.lanlink

import android.util.Log
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Owns the TCP transport for exactly one peer conversation at a time (this app is a
 * 1:1 intercom). A single full-duplex socket carries audio both ways; the device with
 * the lexicographically smaller uid always dials out, the other only accepts — this
 * avoids any connect race ("glare") without a negotiation round-trip.
 *
 * Reliability/order comes from TCP itself. The `seq` counters and [SendBuffer] only
 * matter across reconnects: on (re)connect both sides exchange the last contiguous
 * seq they have already received, so the sender can replay exactly the gap — nothing
 * is dropped, nothing is duplicated, at the cost of some added latency after an
 * outage (which is the desired trade-off: completeness over latency).
 */
class LanAudioLink(private val scope: CoroutineScope) {

    sealed class LinkState {
        data object Idle : LinkState()
        data class Connecting(val peerUid: String) : LinkState()
        data class Connected(val peerUid: String) : LinkState()
        data class Disconnected(val peerUid: String) : LinkState()
    }

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incomingAudio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingAudio: SharedFlow<ByteArray> = _incomingAudio.asSharedFlow()

    /** Called with the uid of an inbound peer when no target was selected yet. */
    var onInboundPeer: ((String) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    val localPort: Int get() = serverSocket?.localPort ?: 0

    private var localUid: String = ""
    @Volatile private var targetPeer: LanPeer? = null

    private var acceptJob: Job? = null
    private var connectJob: Job? = null
    private var session: PeerSession? = null

    private var currentPeerUid: String? = null
    private val sendBuffer = SendBuffer()
    @Volatile private var outSeq = 0L
    @Volatile private var lastContiguousInSeq = -1L

    fun startServer(localUid: String) {
        this.localUid = localUid
        if (serverSocket != null) return
        serverSocket = runCatching { ServerSocket(0) }.getOrNull()
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop() }
    }

    fun stopServer() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** Sets (or clears, with null) which peer this device should be talking to. */
    @Synchronized
    fun setTarget(peer: LanPeer?) {
        val previous = targetPeer
        targetPeer = peer

        if (peer == null) {
            connectJob?.cancel()
            connectJob = null
            session?.close()
            session = null
            currentPeerUid = null
            _state.value = LinkState.Idle
            return
        }

        if (previous?.uid != peer.uid) {
            session?.close()
            session = null
            currentPeerUid = peer.uid
            sendBuffer.clear()
            outSeq = 0
            lastContiguousInSeq = -1
        }

        if (isClientRoleFor(peer.uid) && (connectJob == null || connectJob?.isActive == false)) {
            connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
        }
    }

    /** Buffers immediately; flushes to the wire if a session is live. */
    fun send(pcm: ByteArray) {
        val seq = outSeq++
        sendBuffer.add(seq, pcm)
        session?.takeIf { !it.closed }?.sendFrame(LanFrame.Audio(seq, pcm))
    }

    private fun isClientRoleFor(peerUid: String): Boolean = localUid < peerUid

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            val peer = targetPeer ?: return
            if (!isClientRoleFor(peer.uid)) return
            if (session != null) {
                delay(500)
                continue
            }
            if (peer.host.isBlank() || peer.port <= 0) {
                delay(1_000)
                continue
            }
            _state.value = LinkState.Connecting(peer.uid)
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(peer.host, peer.port), CONNECT_TIMEOUT_MS)
                performHandshakeAndAdopt(peer.uid, socket)
            } catch (e: IOException) {
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun acceptLoop() {
        val server = serverSocket ?: return
        while (currentCoroutineContext().isActive) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            scope.launch(Dispatchers.IO) { handleAcceptedSocket(socket) }
        }
    }

    private suspend fun handleAcceptedSocket(socket: Socket) {
        try {
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val hello = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { LanProtocol.readFrame(input) }
            }
            if (hello !is LanFrame.Hello) {
                runCatching { socket.close() }
                return
            }
            val peerUid = hello.uid
            val busyWithSomeoneElse = session?.let { !it.closed && it.peerUid != peerUid } == true
            if (busyWithSomeoneElse) {
                runCatching { socket.close() }
                return
            }
            if (targetPeer == null) {
                onInboundPeer?.invoke(peerUid)
            }
            if (currentPeerUid != peerUid) {
                currentPeerUid = peerUid
                sendBuffer.clear()
                outSeq = 0
                lastContiguousInSeq = -1
            }
            LanProtocol.writeFrame(out, LanFrame.Hello(localUid, lastContiguousInSeq))
            adoptSession(peerUid, socket, out, input, hello.lastContiguousSeq)
        } catch (e: IOException) {
            runCatching { socket.close() }
        }
    }

    private suspend fun performHandshakeAndAdopt(peerUid: String, socket: Socket) {
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        LanProtocol.writeFrame(out, LanFrame.Hello(localUid, lastContiguousInSeq))
        val reply = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { LanProtocol.readFrame(input) }
        }
        if (reply !is LanFrame.Hello || reply.uid != peerUid) {
            runCatching { socket.close() }
            return
        }
        adoptSession(peerUid, socket, out, input, reply.lastContiguousSeq)
    }

    private fun adoptSession(
        peerUid: String,
        socket: Socket,
        out: DataOutputStream,
        input: DataInputStream,
        peerAnnouncedLastContiguousSeq: Long,
    ) {
        session?.close()
        val newSession = PeerSession(peerUid, socket, out, input)
        session = newSession
        _state.value = LinkState.Connected(peerUid)
        sendBuffer.trimTo(peerAnnouncedLastContiguousSeq)
        newSession.start()
        sendBuffer.replayFrom(peerAnnouncedLastContiguousSeq).forEach {
            newSession.sendFrame(LanFrame.Audio(it.seq, it.data))
        }
    }

    private fun onSessionClosed(peerUid: String) {
        if (session?.peerUid != peerUid) return
        session = null
        _state.value = LinkState.Disconnected(peerUid)
        val target = targetPeer
        if (target != null && target.uid == peerUid && isClientRoleFor(peerUid)) {
            if (connectJob?.isActive != true) {
                connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
            }
        }
    }

    private inner class PeerSession(
        val peerUid: String,
        private val socket: Socket,
        private val out: DataOutputStream,
        private val input: DataInputStream,
    ) {
        private val writeLock = Any()
        private var readerJob: Job? = null
        private var ackJob: Job? = null
        private var pingJob: Job? = null
        @Volatile private var lastActivityMs = System.currentTimeMillis()
        @Volatile var closed = false
            private set

        fun start() {
            readerJob = scope.launch(Dispatchers.IO) { readLoop() }
            ackJob = scope.launch(Dispatchers.IO) { ackLoop() }
            pingJob = scope.launch(Dispatchers.IO) { pingLoop() }
        }

        fun sendFrame(frame: LanFrame) {
            if (closed) return
            try {
                synchronized(writeLock) { LanProtocol.writeFrame(out, frame) }
            } catch (e: IOException) {
                close()
            }
        }

        private suspend fun readLoop() {
            try {
                while (currentCoroutineContext().isActive) {
                    val frame = LanProtocol.readFrame(input) ?: break
                    lastActivityMs = System.currentTimeMillis()
                    when (frame) {
                        is LanFrame.Audio -> {
                            if (frame.seq > lastContiguousInSeq) {
                                lastContiguousInSeq = frame.seq
                                _incomingAudio.emit(frame.pcm)
                            }
                        }
                        is LanFrame.Ack -> sendBuffer.trimTo(frame.lastContiguousSeq)
                        is LanFrame.Ping -> sendFrame(LanFrame.Pong(frame.timestampMs))
                        is LanFrame.Pong -> Unit
                        is LanFrame.Hello -> Unit
                    }
                }
            } catch (e: IOException) {
                Log.d(TAG, "session with $peerUid read error: ${e.message}")
            } finally {
                close()
            }
        }

        private suspend fun ackLoop() {
            while (currentCoroutineContext().isActive && !closed) {
                delay(ACK_INTERVAL_MS)
                sendFrame(LanFrame.Ack(lastContiguousInSeq))
            }
        }

        private suspend fun pingLoop() {
            while (currentCoroutineContext().isActive && !closed) {
                delay(PING_INTERVAL_MS)
                if (System.currentTimeMillis() - lastActivityMs > PEER_TIMEOUT_MS) {
                    close()
                    return
                }
                sendFrame(LanFrame.Ping(System.currentTimeMillis()))
            }
        }

        fun close() {
            if (closed) return
            closed = true
            readerJob?.cancel()
            ackJob?.cancel()
            pingJob?.cancel()
            runCatching { socket.close() }
            onSessionClosed(peerUid)
        }
    }

    companion object {
        private const val TAG = "LanAudioLink"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val RETRY_DELAY_MS = 1_000L
        private const val ACK_INTERVAL_MS = 250L
        private const val PING_INTERVAL_MS = 5_000L
        private const val PEER_TIMEOUT_MS = 12_000L
    }
}
