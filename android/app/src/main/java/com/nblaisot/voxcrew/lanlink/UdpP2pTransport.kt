package com.nblaisot.voxcrew.lanlink

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Direct-internet [FrameTransport] for [PeerLink]: a UDP socket hole-punched to the
 * peer's public (and, as a bonus for the same-NAT case, local) endpoint, discovered
 * via [StunClient] and exchanged through the backend rendezvous (`p2p_endpoints`).
 *
 * Unlike TCP, UDP gives no delivery/ordering guarantee, so this transport does its
 * own lightweight reliability on top of [PeerLink]'s sequence numbers: it punches
 * with the peer's own Hello frame (which doubles as the resume handshake once a
 * reply is heard from either candidate address), sends periodic keepalives to hold
 * the NAT mapping open, and retransmits the whole unacknowledged window (Go-Back-N)
 * whenever the oldest unacked frame has been sitting for longer than [rtoMs].
 */
class UdpP2pTransport(
    private val scope: CoroutineScope,
    private val peerLink: PeerLink,
    private val punchIntervalMs: Long = PUNCH_INTERVAL_MS,
    private val punchDurationMs: Long = PUNCH_DURATION_MS,
    private val keepaliveIntervalMs: Long = KEEPALIVE_INTERVAL_MS,
    private val rtoMs: Long = RTO_MS,
    private val rtoCheckIntervalMs: Long = RTO_CHECK_INTERVAL_MS,
) : FrameTransport {
    override val label: String = "Internet direct"

    private var socket: DatagramSocket? = null
    private var localUid: String = ""
    private var peerUid: String = ""
    @Volatile private var peerCandidates: List<InetSocketAddress> = emptyList()
    @Volatile private var confirmedAddress: InetSocketAddress? = null
    @Volatile private var connected = false
    @Volatile private var handshakeSent = false

    private var punchJob: Job? = null
    private var receiveJob: Job? = null
    private var retransmitJob: Job? = null

    /** Opens the local socket (idempotent) so it can be used for both STUN discovery and punching. */
    fun openSocket(): DatagramSocket {
        socket?.let { return it }
        val newSocket = DatagramSocket(0)
        socket = newSocket
        return newSocket
    }

    val localSocketPort: Int get() = socket?.localPort ?: 0

    /** Must be called on the socket returned by [openSocket] (NAT mapping is per-socket). */
    fun discoverPublicEndpoint(stunHost: String, stunPort: Int): StunClient.Endpoint? {
        val active = socket ?: return null
        return StunClient.discover(active, stunHost, stunPort)
    }

    /** Starts punching towards [candidates] (public, then local, endpoints of the peer). */
    fun start(localUid: String, peerUid: String, candidates: List<InetSocketAddress>) {
        val active = openSocket()
        this.localUid = localUid
        this.peerUid = peerUid
        this.peerCandidates = candidates
        confirmedAddress = null
        connected = false
        handshakeSent = false
        peerLink.markConnecting(peerUid)
        receiveJob?.cancel()
        receiveJob = scope.launch(Dispatchers.IO) { receiveLoop(active) }
        punchJob?.cancel()
        punchJob = scope.launch(Dispatchers.IO) { punchLoop(active) }
        retransmitJob?.cancel()
        retransmitJob = scope.launch(Dispatchers.IO) { retransmitLoop() }
    }

    override fun sendFrame(frame: LanFrame) {
        val address = confirmedAddress ?: return
        sendTo(frame, address)
    }

    override fun dropAndRetry() {
        confirmedAddress = null
        connected = false
        handshakeSent = false
        val active = socket ?: return
        punchJob?.cancel()
        punchJob = scope.launch(Dispatchers.IO) { punchLoop(active) }
    }

    override fun stop() {
        punchJob?.cancel()
        receiveJob?.cancel()
        retransmitJob?.cancel()
        punchJob = null
        receiveJob = null
        retransmitJob = null
        runCatching { socket?.close() }
        socket = null
        confirmedAddress = null
        connected = false
        handshakeSent = false
    }

    private fun sendTo(frame: LanFrame, address: InetSocketAddress) {
        val bytes = LanProtocol.encodeFrame(frame)
        try {
            socket?.send(DatagramPacket(bytes, bytes.size, address))
        } catch (e: IOException) {
            Log.d(TAG, "send to $address failed: ${e.message}")
        }
    }

    private suspend fun punchLoop(socket: DatagramSocket) {
        val deadline = System.currentTimeMillis() + punchDurationMs
        while (currentCoroutineContext().isActive && confirmedAddress == null && System.currentTimeMillis() < deadline) {
            val hello = LanFrame.Hello(localUid, peerLink.lastContiguousInSeq())
            peerCandidates.forEach { candidate -> sendTo(hello, candidate) }
            delay(punchIntervalMs)
        }
        // Whether or not punching succeeded, keep the NAT mapping warm — a reply
        // arriving later still confirms the path and completes the handshake.
        while (currentCoroutineContext().isActive) {
            delay(keepaliveIntervalMs)
            val address = confirmedAddress
            if (address != null) {
                sendTo(LanFrame.Ping(System.currentTimeMillis()), address)
            } else {
                peerCandidates.forEach { candidate -> sendTo(LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()), candidate) }
            }
        }
    }

    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        try {
            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val fromAddress = InetSocketAddress(packet.address, packet.port)
                val frame = LanProtocol.decodeFrame(packet.data.copyOf(packet.length)) ?: continue
                handleFrame(frame, fromAddress)
            }
        } catch (e: IOException) {
            Log.d(TAG, "receive loop ended: ${e.message}")
        }
    }

    private fun handleFrame(frame: LanFrame, fromAddress: InetSocketAddress) {
        val existing = confirmedAddress
        if (existing == null) {
            confirmedAddress = fromAddress
        } else if (existing != fromAddress) {
            return // ignore stray packets from an unconfirmed source once a path is settled
        }
        when (frame) {
            is LanFrame.Hello -> {
                if (frame.uid != peerUid) return
                if (!handshakeSent) {
                    handshakeSent = true
                    sendTo(LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()), fromAddress)
                }
                if (!connected) {
                    connected = true
                    peerLink.onHandshakeComplete(this, peerUid, frame.lastContiguousSeq)
                }
            }
            else -> {
                if (!connected) return // wait for the handshake so seq bookkeeping stays consistent
                peerLink.onFrameReceived(this, frame)
            }
        }
    }

    private suspend fun retransmitLoop() {
        var lastResendMs = 0L
        while (currentCoroutineContext().isActive) {
            delay(rtoCheckIntervalMs)
            if (!connected) continue
            val now = System.currentTimeMillis()
            if (peerLink.oldestUnackedAgeMs() > rtoMs && now - lastResendMs > rtoMs) {
                lastResendMs = now
                val address = confirmedAddress ?: continue
                peerLink.unacknowledgedFrames().forEach { entry -> sendTo(LanFrame.Audio(entry.seq, entry.data), address) }
            }
        }
    }

    companion object {
        private const val TAG = "UdpP2pTransport"
        const val DEFAULT_STUN_HOST = "stun.l.google.com"
        const val DEFAULT_STUN_PORT = 19302
        private const val PUNCH_INTERVAL_MS = 300L
        private const val PUNCH_DURATION_MS = 6_000L
        private const val KEEPALIVE_INTERVAL_MS = 15_000L
        private const val RTO_MS = 800L
        private const val RTO_CHECK_INTERVAL_MS = 250L
    }
}
