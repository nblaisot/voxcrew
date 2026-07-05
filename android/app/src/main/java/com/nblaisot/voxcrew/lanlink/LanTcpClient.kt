package com.nblaisot.voxcrew.lanlink

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
import java.net.Socket

/**
 * Per-peer LAN [FrameTransport]: outbound dial (when client role) and one TCP session
 * bound to a single [PeerLink]. Inbound accepts are handed off by [LanTcpServer].
 */
class LanTcpClient(
    private val scope: CoroutineScope,
    private val localUid: String,
    private val peerLink: PeerLink,
    private val server: LanTcpServer,
) : FrameTransport {
    override val label: String = "Local"

    private val peerUid: String get() = peerLink.selectedPeerUid.orEmpty()

    @Volatile private var targetPeer: LanPeer? = null
    private var connectJob: Job? = null
    private var session: LanTcpSession? = null

    fun lastContiguousInSeq(): Long = peerLink.lastContiguousInSeq()

    /** Sets (or clears) which peer this client dials on the LAN. */
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

    internal fun adoptInboundSession(
        peerUid: String,
        socket: Socket,
        out: DataOutputStream,
        input: DataInputStream,
        peerAnnouncedLastContiguousSeq: Long,
    ) {
        if (this.peerUid != peerUid) return
        adoptSession(peerUid, socket, out, input, peerAnnouncedLastContiguousSeq)
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

    private suspend fun performHandshakeAndAdopt(peerUid: String, socket: Socket) {
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        LanProtocol.writeFrame(out, LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()))
        val reply = withTimeoutOrNull(LanTcpServer.HANDSHAKE_TIMEOUT_MS) {
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
        val newSession = LanTcpSession(
            scope = scope,
            peerUid = peerUid,
            socket = socket,
            out = out,
            input = input,
            peerLink = peerLink,
            transport = this,
            onClosed = ::onSessionClosed,
        )
        session = newSession
        newSession.start()
        peerLink.onHandshakeComplete(this, peerUid, peerAnnouncedLastContiguousSeq)
    }

    private fun onSessionClosed(closedPeerUid: String) {
        if (session?.peerUid != closedPeerUid) return
        session = null
        peerLink.onDisconnected(this, closedPeerUid)
        val target = targetPeer
        if (target != null && target.uid == closedPeerUid && isClientRoleFor(closedPeerUid)) {
            if (connectJob?.isActive != true) {
                connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val RETRY_DELAY_MS = 1_000L
    }
}
