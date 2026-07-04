package com.nblaisot.voxcrew.lanlink

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
 * LAN [FrameTransport] for [PeerLink]: a single full-duplex TCP socket per peer
 * conversation. Reliability/order come from TCP itself; this class only needs to
 * establish the socket (dial or accept, deterministic role — the device with the
 * lexicographically smaller uid always dials out, avoiding any connect race without
 * a negotiation round-trip) and perform the HELLO/resume handshake before handing
 * frames to [peerLink].
 */
class LanTcpTransport(
    private val scope: CoroutineScope,
    private val peerLink: PeerLink,
) : FrameTransport {
    override val label: String = "Local"

    /** Called with the uid of an inbound peer when no target was selected yet. */
    var onInboundPeer: ((String) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    val localPort: Int get() = serverSocket?.localPort ?: 0

    private var localUid: String = ""
    @Volatile private var targetPeer: LanPeer? = null

    private var acceptJob: Job? = null
    private var connectJob: Job? = null
    private var session: PeerSession? = null

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

    /** Sets (or clears, with null) which peer this device should be dialing on the LAN. */
    @Synchronized
    fun setTarget(peer: LanPeer?) {
        targetPeer = peer
        if (peer == null) {
            connectJob?.cancel()
            connectJob = null
            session?.close()
            session = null
            return
        }
        if (isClientRoleFor(peer.uid) && (connectJob == null || connectJob?.isActive == false)) {
            connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
        }
    }

    override fun sendFrame(frame: LanFrame) {
        session?.takeIf { !it.closed }?.sendFrame(frame)
    }

    override fun dropAndRetry() {
        session?.close()
    }

    override fun stop() {
        connectJob?.cancel()
        connectJob = null
        session?.close()
        session = null
    }

    private fun isClientRoleFor(peerUid: String): Boolean = localUid < peerUid

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            val peer = targetPeer ?: return
            if (!isClientRoleFor(peer.uid)) return
            if (session?.closed == false) {
                delay(500)
                continue
            }
            if (peer.host.isBlank() || peer.port <= 0) {
                delay(1_000)
                continue
            }
            peerLink.markConnecting(peer.uid)
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
            LanProtocol.writeFrame(out, LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()))
            adoptSession(peerUid, socket, out, input, hello.lastContiguousSeq)
        } catch (e: IOException) {
            runCatching { socket.close() }
        }
    }

    private suspend fun performHandshakeAndAdopt(peerUid: String, socket: Socket) {
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        LanProtocol.writeFrame(out, LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()))
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
        newSession.start()
        peerLink.onHandshakeComplete(this, peerUid, peerAnnouncedLastContiguousSeq)
    }

    private fun onSessionClosed(peerUid: String) {
        if (session?.peerUid != peerUid) return
        session = null
        peerLink.onDisconnected(this, peerUid)
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
        @Volatile var closed = false
            private set

        fun start() {
            readerJob = scope.launch(Dispatchers.IO) { readLoop() }
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
                    if (frame !is LanFrame.Hello) {
                        peerLink.onFrameReceived(this@LanTcpTransport, frame)
                    }
                }
            } catch (e: IOException) {
                Log.d(TAG, "session with $peerUid read error: ${e.message}")
            } finally {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            readerJob?.cancel()
            runCatching { socket.close() }
            onSessionClosed(peerUid)
        }
    }

    companion object {
        private const val TAG = "LanTcpTransport"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val RETRY_DELAY_MS = 1_000L
    }
}
