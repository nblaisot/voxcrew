package com.nblaisot.voxcrew.lanlink

import com.nblaisot.voxcrew.connectivity.transport.CloudRunSignalingTransport
import com.nblaisot.voxcrew.signaling.ConnectionState
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.UUID

/**
 * One intercom link to a single peer: own [PeerLink], LAN client, UDP punch, and cloud
 * relay, with an independent path watcher so mixed local/cloud groups work transparently.
 */
class PeerConnection(
    val peerUid: String,
    private val scope: CoroutineScope,
    private val localUid: String,
    private val lanServer: LanTcpServer,
    private val sharedUdp: SharedUdpSocket,
    private val cloudTransport: CloudRunSignalingTransport,
    private val isStillWanted: () -> Boolean,
    private val localIpv4Provider: () -> String?,
    private val cloudFallbackEnabled: Boolean = true,
) {
    val peerLink = PeerLink(scope)
    private val lanTcpClient = LanTcpClient(scope, localUid, peerLink, lanServer)
    private val udpTransport = UdpP2pTransport(scope, peerLink, sharedUdp)
    val relayTransport = RelayTransport(scope, peerLink, cloudTransport)

    val linkState: StateFlow<PeerLink.LinkState> = peerLink.state
    val rttMs: StateFlow<Long?> = peerLink.rttMs
    val backlogMs: StateFlow<Long> = peerLink.backlogMs

    private var pathWatchJob: Job? = null
    private var fallbackJob: Job? = null
    @Volatile private var cloudFallbackEngaged = false
    private var stateSinceMs = System.currentTimeMillis()
    private var lastObservedState: PeerLink.LinkState? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        lanServer.registerClient(peerUid, lanTcpClient)
        peerLink.resetFor(peerUid)
        stateSinceMs = System.currentTimeMillis()
        lastObservedState = peerLink.state.value
        startPathWatcher()
    }

    fun stop() {
        if (!started) return
        started = false
        pathWatchJob?.cancel()
        pathWatchJob = null
        deactivateCloudFallback()
        lanTcpClient.stop()
        lanTcpClient.setTarget(null)
        lanServer.unregisterClient(peerUid)
        udpTransport.stop()
        relayTransport.stop()
        peerLink.clear()
    }

    fun updateLanTarget(peer: LanPeer?) {
        if (!started) return
        lanTcpClient.setTarget(peer)
    }

    /**
     * Apply discovery result for this peer: prefer LAN when visible, warm Tailscale standby
     * after a missed beacon, and promote overlay when LAN is gone.
     */
    fun applyPathTargets(
        lanPeer: LanPeer?,
        overlayPeer: LanPeer?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!started) return
        val lan = lanPeer?.takeUnless { it.viaOverlay }
        when {
            lan != null -> {
                val warm = OverlayFailoverPolicy.shouldWarmStandby(
                    lanLastSeenMs = lan.lastSeenMs,
                    nowMs = nowMs,
                    hasOverlayEndpoint = overlayPeer != null,
                    lanStillListed = true,
                )
                if (warm && overlayPeer != null) {
                    lanTcpClient.warmStandby(overlayPeer)
                } else {
                    lanTcpClient.clearStandby()
                }
                val via = (peerLink.state.value as? PeerLink.LinkState.Connected)?.via
                if (via == PathLabels.VPN) {
                    lanTcpClient.switchToLanMakeBeforeBreak(lan)
                } else {
                    lanTcpClient.setTarget(lan)
                }
            }
            overlayPeer != null -> {
                promoteToOverlay(overlayPeer)
            }
            else -> {
                lanTcpClient.setTarget(null)
                onPeerPresenceLost(localOnly = true)
            }
        }
    }

    fun promoteToOverlay(overlayPeer: LanPeer) {
        if (!started) return
        if (lanTcpClient.promoteStandby()) {
            lanTcpClient.setTarget(overlayPeer, preserveSession = true)
            return
        }
        lanTcpClient.setTarget(overlayPeer, forceRestart = true)
    }

    /** LAN beacon for this peer expired — switch to overlay if available, else tear down. */
    fun onLanPeerAbsent(overlayPeer: LanPeer? = null) {
        if (!started) return
        if (overlayPeer != null) {
            promoteToOverlay(overlayPeer)
            return
        }
        lanTcpClient.setTarget(null)
        onPeerPresenceLost(localOnly = true)
    }

    /** Cloud presence lost or relay unavailable — tear down any active audio link. */
    fun onPeerPresenceLost(localOnly: Boolean = false) {
        if (!started) return
        if (localOnly) {
            if ((peerLink.state.value as? PeerLink.LinkState.Connected)?.via == lanTcpClient.label) {
                peerLink.markUnreachable()
            }
        } else {
            peerLink.markUnreachable()
        }
    }

    fun send(payload: ByteArray) {
        if (!started) return
        peerLink.send(payload)
    }

    fun sendMediaActivity(active: Boolean) {
        if (!started) return
        peerLink.sendMediaActivity(active)
    }

    fun handleCloudMessage(envelope: SignalingEnvelope) {
        if (!started || envelope.senderId != peerUid) return
        when (envelope.type) {
            SignalingMessageTypes.P2P_CONNECT_REQUEST -> {
                scope.launch(Dispatchers.IO) { announceEndpoints() }
            }
            SignalingMessageTypes.P2P_ENDPOINTS -> {
                val publicHost = envelope.payload["publicHost"]?.jsonPrimitive?.content ?: return
                val publicPort = envelope.payload["publicPort"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                val localHost = envelope.payload["localHost"]?.jsonPrimitive?.content
                val localPort = envelope.payload["localPort"]?.jsonPrimitive?.content?.toIntOrNull()
                val candidates = buildList {
                    if (localHost != null && localPort != null) add(InetSocketAddress(localHost, localPort))
                    add(InetSocketAddress(publicHost, publicPort))
                }
                cloudFallbackEngaged = true
                udpTransport.start(localUid, peerUid, candidates)
                if (fallbackJob?.isActive != true) {
                    scope.launch(Dispatchers.IO) { announceEndpoints() }
                }
            }
        }
    }

    fun onNetworkChanged() {
        if (!started) return
        val via = (peerLink.state.value as? PeerLink.LinkState.Connected)?.via
        if (via != null && via != lanTcpClient.label) {
            fallbackJob?.cancel()
            cloudFallbackEngaged = true
            fallbackJob = scope.launch(Dispatchers.IO) { announceEndpoints() }
        }
    }

    /** Routes a shared-socket UDP datagram to this peer if relevant. Returns true if handled. */
    fun tryHandleUdpDatagram(data: ByteArray, fromAddress: InetSocketAddress): Boolean {
        if (!started) return false
        if (udpTransport.isInterestedIn(fromAddress)) {
            udpTransport.handleDatagram(data, fromAddress)
            return true
        }
        val frame = LanProtocol.decodeFrame(data) ?: return false
        if (udpTransport.matchesHello(frame)) {
            udpTransport.handleDatagram(data, fromAddress)
            return true
        }
        return false
    }

    private fun startPathWatcher() {
        pathWatchJob?.cancel()
        pathWatchJob = scope.launch(Dispatchers.Default) {
            while (currentCoroutineContext().isActive && started) {
                delay(WATCH_INTERVAL_MS)
                evaluatePath()
            }
        }
    }

    private fun evaluatePath() {
        if (!started || !isStillWanted()) return
        val state = peerLink.state.value
        if (state != lastObservedState) {
            lastObservedState = state
            stateSinceMs = System.currentTimeMillis()
        }
        val elapsed = System.currentTimeMillis() - stateSinceMs
        val connectedVia = (state as? PeerLink.LinkState.Connected)?.via

        if (connectedVia == lanTcpClient.label) {
            val rtt = rttMs.value
            val degraded = (rtt != null && rtt > PeerLink.DEGRADED_RTT_MS) ||
                peerLink.oldestUnackedAgeMs() > PeerLink.DEGRADED_UNACKED_AGE_MS
            if (degraded) triggerCloudFallback() else deactivateCloudFallback()
            return
        }
        if (connectedVia != null) return

        val stalled = (state is PeerLink.LinkState.Idle || state is PeerLink.LinkState.Connecting) &&
            elapsed > LOCAL_DISCOVERY_TIMEOUT_MS
        val lost = state is PeerLink.LinkState.Disconnected && elapsed > LOCAL_LINK_LOST_MS
        if (stalled || lost) triggerCloudFallback()
    }

    private fun triggerCloudFallback() {
        if (!cloudFallbackEnabled) return
        if (fallbackJob?.isActive == true) return
        cloudFallbackEngaged = true
        fallbackJob = scope.launch(Dispatchers.IO) {
            cloudTransport.connectCloud()
            awaitCloudAuthenticated()
            runCatching {
                cloudTransport.send(
                    SignalingEnvelope(
                        type = SignalingMessageTypes.P2P_CONNECT_REQUEST,
                        requestId = UUID.randomUUID().toString(),
                        recipientId = peerUid,
                    ),
                )
            }
            announceEndpoints()
            delay(UDP_CONNECT_GRACE_MS)
            if (currentCoroutineContext().isActive &&
                isStillWanted() &&
                peerLink.state.value !is PeerLink.LinkState.Connected
            ) {
                relayTransport.start(localUid, peerUid)
            }
        }
    }

    private fun deactivateCloudFallback() {
        if (!cloudFallbackEngaged) return
        cloudFallbackEngaged = false
        fallbackJob?.cancel()
        fallbackJob = null
        udpTransport.stop()
        relayTransport.stop()
    }

    private suspend fun awaitCloudAuthenticated() {
        if (cloudTransport.state.value.connectionState == ConnectionState.AUTHENTICATED) return
        withTimeoutOrNull(10_000) {
            cloudTransport.state.first { it.connectionState == ConnectionState.AUTHENTICATED }
        }
    }

    private suspend fun announceEndpoints() {
        cloudTransport.connectCloud()
        awaitCloudAuthenticated()
        withContext(Dispatchers.IO) {
            udpTransport.openSocket()
            val endpoint = udpTransport.discoverPublicEndpoint(
                UdpP2pTransport.DEFAULT_STUN_HOST,
                UdpP2pTransport.DEFAULT_STUN_PORT,
            ) ?: return@withContext
            val local = localIpv4Provider()
            runCatching {
                cloudTransport.send(
                    SignalingEnvelope(
                        type = SignalingMessageTypes.P2P_ENDPOINTS,
                        requestId = UUID.randomUUID().toString(),
                        recipientId = peerUid,
                        payload = buildJsonObject {
                            put("publicHost", JsonPrimitive(endpoint.host))
                            put("publicPort", JsonPrimitive(endpoint.port))
                            if (local != null) {
                                put("localHost", JsonPrimitive(local))
                                put("localPort", JsonPrimitive(udpTransport.localSocketPort))
                            }
                        },
                    ),
                )
            }
        }
    }

    companion object {
        private const val WATCH_INTERVAL_MS = 500L
        private const val LOCAL_DISCOVERY_TIMEOUT_MS = 5_000L
        private const val LOCAL_LINK_LOST_MS = 3_000L
        private const val UDP_CONNECT_GRACE_MS = 7_000L
    }
}

data class PeerMetrics(
    val rttMs: Long? = null,
    val pathLabel: String? = null,
    val backlogMs: Long = 0L,
    val linkState: PeerLink.LinkState = PeerLink.LinkState.Idle,
)
