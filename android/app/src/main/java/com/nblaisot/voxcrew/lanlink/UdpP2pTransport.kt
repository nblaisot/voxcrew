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
 * When constructed with [sharedUdp], the socket and receive loop are owned by
 * [LanIntercomEngine]; this transport only punches/sends and handles demuxed packets
 * via [handleDatagram].
 */
class UdpP2pTransport(
    private val scope: CoroutineScope,
    private val peerLink: PeerLink,
    private val sharedUdp: SharedUdpSocket? = null,
    private val punchIntervalMs: Long = PUNCH_INTERVAL_MS,
    private val punchDurationMs: Long = PUNCH_DURATION_MS,
    private val keepaliveIntervalMs: Long = KEEPALIVE_INTERVAL_MS,
    private val rtoMs: Long = RTO_MS,
    private val rtoCheckIntervalMs: Long = RTO_CHECK_INTERVAL_MS,
) : FrameTransport {
    override val label: String = "Internet direct"

    private var ownedSocket: DatagramSocket? = null
    private var localUid: String = ""
    private var peerUid: String = ""
    @Volatile private var peerCandidates: List<InetSocketAddress> = emptyList()
    @Volatile private var confirmedAddress: InetSocketAddress? = null
    @Volatile private var connected = false
    @Volatile private var handshakeSent = false
    @Volatile private var running = false

    private var punchJob: Job? = null
    private var receiveJob: Job? = null
    private var retransmitJob: Job? = null

    val activePeerUid: String get() = peerUid

    /** Opens the local socket (idempotent) so it can be used for both STUN discovery and punching. */
    fun openSocket(): DatagramSocket {
        sharedUdp?.open()?.let { return it }
        ownedSocket?.let { return it }
        val newSocket = DatagramSocket(0)
        ownedSocket = newSocket
        return newSocket
    }

    val localSocketPort: Int
        get() = sharedUdp?.localPort ?: ownedSocket?.localPort ?: 0

    /** Must be called on the socket returned by [openSocket] (NAT mapping is per-socket). */
    fun discoverPublicEndpoint(stunHost: String, stunPort: Int): StunClient.Endpoint? {
        val active = ownedSocket ?: sharedUdp?.open() ?: return null
        return StunClient.discover(active, stunHost, stunPort)
    }

    /** Starts punching towards [candidates] (public, then local, endpoints of the peer). */
    fun start(localUid: String, peerUid: String, candidates: List<InetSocketAddress>) {
        openSocket()
        this.localUid = localUid
        this.peerUid = peerUid
        this.peerCandidates = candidates
        confirmedAddress = null
        connected = false
        handshakeSent = false
        running = true
        peerLink.markConnecting(peerUid)
        punchJob?.cancel()
        punchJob = scope.launch(Dispatchers.IO) { punchLoop() }
        retransmitJob?.cancel()
        retransmitJob = scope.launch(Dispatchers.IO) { retransmitLoop() }
        if (sharedUdp == null) {
            receiveJob?.cancel()
            val socket = ownedSocket ?: return
            receiveJob = scope.launch(Dispatchers.IO) { receiveLoop(socket) }
        }
    }

    /** Called by the engine's shared UDP receiver when [sharedUdp] is in use. */
    fun handleDatagram(data: ByteArray, fromAddress: InetSocketAddress) {
        if (!running || peerUid.isBlank()) return
        val frame = LanProtocol.decodeFrame(data) ?: return
        handleFrame(frame, fromAddress)
    }

    /** True if this transport may accept packets from [address] (candidate or confirmed). */
    fun isInterestedIn(address: InetSocketAddress): Boolean {
        if (!running || peerUid.isBlank()) return false
        confirmedAddress?.let { return it == address }
        return peerCandidates.any { it == address }
    }

    /** True if [frame] is a Hello from the peer this transport is punching towards. */
    fun matchesHello(frame: LanFrame): Boolean {
        return running && frame is LanFrame.Hello && frame.uid == peerUid
    }

    override fun sendFrame(frame: LanFrame) {
        val address = confirmedAddress ?: return
        sendTo(frame, address)
    }

    override fun dropAndRetry() {
        confirmedAddress = null
        connected = false
        handshakeSent = false
        if (!running) return
        punchJob?.cancel()
        punchJob = scope.launch(Dispatchers.IO) { punchLoop() }
    }

    override fun stop() {
        running = false
        punchJob?.cancel()
        receiveJob?.cancel()
        retransmitJob?.cancel()
        punchJob = null
        receiveJob = null
        retransmitJob = null
        if (sharedUdp == null) {
            runCatching { ownedSocket?.close() }
            ownedSocket = null
        }
        confirmedAddress = null
        connected = false
        handshakeSent = false
        peerUid = ""
        peerCandidates = emptyList()
    }

    private fun activeSocket(): DatagramSocket? = sharedUdp?.open() ?: ownedSocket

    private fun sendTo(frame: LanFrame, address: InetSocketAddress) {
        val bytes = LanProtocol.encodeFrame(frame)
        try {
            activeSocket()?.send(DatagramPacket(bytes, bytes.size, address))
        } catch (e: IOException) {
            Log.d(TAG, "send to $address failed: ${e.message}")
        }
    }

    private suspend fun punchLoop() {
        val deadline = System.currentTimeMillis() + punchDurationMs
        while (currentCoroutineContext().isActive && running && confirmedAddress == null &&
            System.currentTimeMillis() < deadline
        ) {
            val hello = LanFrame.Hello(localUid, peerLink.lastContiguousInSeq())
            peerCandidates.forEach { candidate -> sendTo(hello, candidate) }
            delay(punchIntervalMs)
        }
        while (currentCoroutineContext().isActive && running) {
            delay(keepaliveIntervalMs)
            val address = confirmedAddress
            if (address != null) {
                sendTo(LanFrame.Ping(System.currentTimeMillis()), address)
            } else {
                peerCandidates.forEach { candidate ->
                    sendTo(LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()), candidate)
                }
            }
        }
    }

    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        try {
            while (currentCoroutineContext().isActive && running) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val fromAddress = InetSocketAddress(packet.address, packet.port)
                handleDatagram(packet.data.copyOf(packet.length), fromAddress)
            }
        } catch (e: IOException) {
            Log.d(TAG, "receive loop ended: ${e.message}")
        }
    }

    private fun handleFrame(frame: LanFrame, fromAddress: InetSocketAddress) {
        val existing = confirmedAddress
        if (existing == null) {
            if (frame is LanFrame.Hello && frame.uid != peerUid) return
            confirmedAddress = fromAddress
        } else if (existing != fromAddress) {
            return
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
                if (!connected) return
                peerLink.onFrameReceived(this, frame)
            }
        }
    }

    private suspend fun retransmitLoop() {
        var lastResendMs = 0L
        while (currentCoroutineContext().isActive && running) {
            delay(rtoCheckIntervalMs)
            if (!connected) continue
            val now = System.currentTimeMillis()
            if (peerLink.oldestUnackedAgeMs() > rtoMs && now - lastResendMs > rtoMs) {
                lastResendMs = now
                val address = confirmedAddress ?: continue
                peerLink.unacknowledgedFrames().forEach { entry ->
                    sendTo(entry.toFrame(), address)
                }
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
