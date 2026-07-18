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
 * Per-peer LAN/overlay [FrameTransport]: outbound dial (when client role) and one TCP session
 * bound to a single [PeerLink]. Inbound accepts are handed off by [LanTcpServer].
 *
 * Host switches (LAN ↔ Tailscale) close the current session and interrupt any in-flight
 * [Socket.connect] so failover is not blocked by a stale 3s LAN dial.
 */
class LanTcpClient(
    private val scope: CoroutineScope,
    private val localUid: String,
    private val peerLink: PeerLink,
    private val server: LanTcpServer,
) : FrameTransport {
    override val label: String
        get() {
            val peer = targetPeer
            return when {
                peer?.viaOverlay == true -> "VPN"
                peer != null && TailscaleInterface.isTailscaleAddress(peer.host) -> "VPN"
                else -> "Local"
            }
        }

    private val peerUid: String get() = peerLink.selectedPeerUid.orEmpty()

    @Volatile private var targetPeer: LanPeer? = null
    private var connectJob: Job? = null
    private var session: LanTcpSession? = null
    @Volatile private var connectingSocket: Socket? = null
    @Volatile private var standbyInbound: ParkedSession? = null
    @Volatile private var standbyOutbound: ParkedSession? = null
    private var standbyDialJob: Job? = null

    fun lastContiguousInSeq(): Long = peerLink.lastContiguousInSeq()

    fun hasHealthyLocalSession(): Boolean {
        val s = session
        return s != null && !s.closed && label == "Local"
    }

    fun hasStandbyReady(): Boolean =
        standbyOutbound?.isOpen() == true || standbyInbound?.isOpen() == true

    /** Sets (or clears) which peer this client dials. Host changes force a re-dial. */
    @Synchronized
    fun setTarget(
        peer: LanPeer?,
        forceRestart: Boolean = false,
        preserveSession: Boolean = false,
    ) {
        val previous = targetPeer
        targetPeer = peer
        if (peer == null) {
            cancelConnectInFlight()
            connectJob?.cancel()
            connectJob = null
            clearStandby()
            session?.close()
            session = null
            return
        }
        val hostChanged = previous != null && (
            previous.host != peer.host ||
                previous.port != peer.port ||
                previous.viaOverlay != peer.viaOverlay
        )
        val mustRestart = !preserveSession && (forceRestart || hostChanged)
        if (mustRestart) {
            cancelConnectInFlight()
            connectJob?.cancel()
            connectJob = null
            if (session?.closed == false) {
                session?.close()
                session = null
            }
        }
        if (isClientRoleFor(peer.uid) &&
            session?.closed != false &&
            (connectJob == null || connectJob?.isActive == false)
        ) {
            connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
        }
    }

    /**
     * Speculative Tailscale dial: completes Hello but does not become the active [PeerLink]
     * transport until [promoteStandby].
     */
    @Synchronized
    fun warmStandby(overlayPeer: LanPeer) {
        if (!overlayPeer.viaOverlay && !TailscaleInterface.isTailscaleAddress(overlayPeer.host)) return
        if (hasStandbyReady()) return
        if (standbyDialJob?.isActive == true) return
        if (!isClientRoleFor(overlayPeer.uid)) return
        standbyDialJob = scope.launch(Dispatchers.IO) { dialStandby(overlayPeer) }
    }

    @Synchronized
    fun clearStandby() {
        standbyDialJob?.cancel()
        standbyDialJob = null
        standbyOutbound?.close()
        standbyOutbound = null
        standbyInbound?.close()
        standbyInbound = null
    }

    /** Promote a parked standby session into the active PeerLink path, if available. */
    @Synchronized
    fun promoteStandby(): Boolean {
        val parked = standbyOutbound?.takeIf { it.isOpen() }
            ?: standbyInbound?.takeIf { it.isOpen() }
            ?: return false
        standbyOutbound = null
        standbyInbound = null
        standbyDialJob?.cancel()
        standbyDialJob = null
        cancelConnectInFlight()
        connectJob?.cancel()
        connectJob = null
        adoptParkedSession(parked)
        return true
    }

    /**
     * Dial [lanPeer] without dropping the current overlay session until Hello succeeds
     * (make-before-break back to LAN).
     */
    @Synchronized
    fun switchToLanMakeBeforeBreak(lanPeer: LanPeer) {
        targetPeer = lanPeer
        if (!isClientRoleFor(lanPeer.uid)) return
        if (connectJob?.isActive == true) return
        connectJob = scope.launch(Dispatchers.IO) {
            dialMakeBeforeBreak(lanPeer)
        }
    }

    override fun sendFrame(frame: LanFrame) {
        session?.takeIf { !it.closed }?.sendFrame(frame)
    }

    override fun dropAndRetry() {
        session?.close()
    }

    override fun stop() {
        cancelConnectInFlight()
        connectJob?.cancel()
        connectJob = null
        clearStandby()
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
        val remoteHost = socket.inetAddress?.hostAddress
        val fromOverlay = remoteHost != null && TailscaleInterface.isTailscaleAddress(remoteHost)
        if (hasHealthyLocalSession() && fromOverlay) {
            standbyInbound?.close()
            standbyInbound = ParkedSession(
                peerUid = peerUid,
                socket = socket,
                out = out,
                input = input,
                peerAnnouncedLastContiguousSeq = peerAnnouncedLastContiguousSeq,
            )
            return
        }
        // LAN inbound while on Tailscale: prefer LAN immediately (make-before-break).
        adoptSession(peerUid, socket, out, input, peerAnnouncedLastContiguousSeq)
    }

    private fun isClientRoleFor(peerUid: String): Boolean = localUid < peerUid

    private fun cancelConnectInFlight() {
        runCatching { connectingSocket?.close() }
        connectingSocket = null
    }

    private fun connectTimeoutMs(peer: LanPeer): Int =
        if (peer.viaOverlay || TailscaleInterface.isTailscaleAddress(peer.host)) {
            OVERLAY_CONNECT_TIMEOUT_MS
        } else {
            LAN_CONNECT_TIMEOUT_MS
        }

    private fun retryDelayMs(peer: LanPeer): Long =
        if (peer.viaOverlay || TailscaleInterface.isTailscaleAddress(peer.host)) {
            OVERLAY_RETRY_DELAY_MS
        } else {
            LAN_RETRY_DELAY_MS
        }

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
            val socket = Socket()
            connectingSocket = socket
            try {
                socket.connect(InetSocketAddress(peer.host, peer.port), connectTimeoutMs(peer))
                if (connectingSocket !== socket) {
                    runCatching { socket.close() }
                    continue
                }
                connectingSocket = null
                performHandshakeAndAdopt(peer.uid, socket)
            } catch (e: IOException) {
                connectingSocket = null
                runCatching { socket.close() }
                delay(retryDelayMs(peer))
            }
        }
    }

    private suspend fun dialStandby(overlayPeer: LanPeer) {
        if (overlayPeer.host.isBlank() || overlayPeer.port <= 0) return
        val socket = Socket()
        try {
            socket.connect(
                InetSocketAddress(overlayPeer.host, overlayPeer.port),
                OVERLAY_CONNECT_TIMEOUT_MS,
            )
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            LanProtocol.writeFrame(out, LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()))
            val reply = withTimeoutOrNull(LanTcpServer.HANDSHAKE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { LanProtocol.readFrame(input) }
            }
            if (reply !is LanFrame.Hello || reply.uid != overlayPeer.uid) {
                runCatching { socket.close() }
                return
            }
            if (!currentCoroutineContext().isActive) {
                runCatching { socket.close() }
                return
            }
            // Remote may reject/park while its LAN is healthy; only keep if still open.
            if (socket.isClosed) return
            standbyOutbound?.close()
            standbyOutbound = ParkedSession(
                peerUid = overlayPeer.uid,
                socket = socket,
                out = out,
                input = input,
                peerAnnouncedLastContiguousSeq = reply.lastContiguousSeq,
            )
        } catch (_: IOException) {
            runCatching { socket.close() }
        }
    }

    private suspend fun dialMakeBeforeBreak(lanPeer: LanPeer) {
        if (lanPeer.host.isBlank() || lanPeer.port <= 0) return
        val socket = Socket()
        connectingSocket = socket
        try {
            socket.connect(InetSocketAddress(lanPeer.host, lanPeer.port), LAN_CONNECT_TIMEOUT_MS)
            if (connectingSocket !== socket) {
                runCatching { socket.close() }
                return
            }
            connectingSocket = null
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            LanProtocol.writeFrame(out, LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()))
            val reply = withTimeoutOrNull(LanTcpServer.HANDSHAKE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { LanProtocol.readFrame(input) }
            }
            if (reply !is LanFrame.Hello || reply.uid != lanPeer.uid) {
                runCatching { socket.close() }
                return
            }
            adoptSession(lanPeer.uid, socket, out, input, reply.lastContiguousSeq)
        } catch (_: IOException) {
            connectingSocket = null
            runCatching { socket.close() }
            // Fall back to normal reconnect loop.
            if (targetPeer?.uid == lanPeer.uid && isClientRoleFor(lanPeer.uid)) {
                if (session?.closed != false) {
                    connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
                }
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

    private fun adoptParkedSession(parked: ParkedSession) {
        session?.close()
        val newSession = LanTcpSession(
            scope = scope,
            peerUid = parked.peerUid,
            socket = parked.socket,
            out = parked.out,
            input = parked.input,
            peerLink = peerLink,
            transport = this,
            onClosed = ::onSessionClosed,
        )
        session = newSession
        newSession.start()
        peerLink.onHandshakeComplete(this, parked.peerUid, parked.peerAnnouncedLastContiguousSeq)
    }

    private fun adoptSession(
        peerUid: String,
        socket: Socket,
        out: DataOutputStream,
        input: DataInputStream,
        peerAnnouncedLastContiguousSeq: Long,
    ) {
        clearStandby()
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

    private data class ParkedSession(
        val peerUid: String,
        val socket: Socket,
        val out: DataOutputStream,
        val input: DataInputStream,
        val peerAnnouncedLastContiguousSeq: Long,
    ) {
        fun isOpen(): Boolean = !socket.isClosed && socket.isConnected
        fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val LAN_CONNECT_TIMEOUT_MS = 2_000
        private const val OVERLAY_CONNECT_TIMEOUT_MS = 1_000
        private const val LAN_RETRY_DELAY_MS = 500L
        private const val OVERLAY_RETRY_DELAY_MS = 250L
    }
}
