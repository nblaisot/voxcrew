package com.nblaisot.voxcrew.lanlink

import android.util.Log
import com.nblaisot.voxcrew.connectivity.NetworkSocketBinder
import com.nblaisot.voxcrew.relay.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One intercom link to a single peer: own [PeerLink] and LAN TCP client, with optional
 * Tailscale overlay failover and optional Cloud (UUID relay) dial.
 */
class PeerConnection(
    val peerUid: String,
    private val scope: CoroutineScope,
    private val localUid: String,
    private val lanServer: LanTcpServer,
    networkSocketBinder: NetworkSocketBinder,
    inboundRouteResolver: (java.net.Socket) -> RoutedSocketPath?,
    private val isStillWanted: () -> Boolean,
    private val overlayPeerProvider: () -> RoutedPeerTarget? = { null },
    private val lanPeerProvider: () -> RoutedPeerTarget? = { null },
    private val relayClientProvider: () -> RelayClient? = { null },
    relayOfferProvider: () -> com.nblaisot.voxcrew.relay.RelayConfigLink? = { null },
    onRelayOffer: (peerUid: String, offer: com.nblaisot.voxcrew.relay.RelayConfigLink) -> Unit = { _, _ -> },
) {
    val peerLink = PeerLink(scope)
    private val lanTcpClient = LanTcpClient(
        scope,
        localUid,
        peerLink,
        lanServer,
        networkSocketBinder,
        inboundRouteResolver,
        relayOfferProvider = relayOfferProvider,
    ).also { client ->
        client.onRelayOffer = onRelayOffer
    }

    val linkState: StateFlow<PeerLink.LinkState> = peerLink.state
    val rttMs: StateFlow<Long?> = peerLink.rttMs
    val backlogMs: StateFlow<Long> = peerLink.backlogMs

    private var started = false
    private var linkDeathWatchJob: Job? = null
    private var cloudDialJob: Job? = null
    /** After a *real* LAN dial failure, prefer overlay/cloud until a network reset or LAN Connected. */
    @Volatile private var lanDialFailed = false
    @Volatile private var cloudDialFailed = false

    fun start() {
        if (started) return
        started = true
        lanServer.registerClient(peerUid, lanTcpClient)
        peerLink.resetFor(peerUid)
        lanTcpClient.onLanDialFailed = {
            if (started) {
                lanDialFailed = true
                val overlay = overlayPeerProvider()
                if (overlay != null) {
                    Log.i(
                        TAG,
                        "LAN dial failed for $peerUid; switching to overlay ${overlay.peer.host}",
                    )
                    promoteToOverlay(overlay)
                } else if (relayClientProvider()?.isReady() == true) {
                    Log.i(TAG, "LAN dial failed for $peerUid; switching to cloud")
                    promoteToCloud()
                }
            }
        }
        watchLinkDeath()
    }

    fun stop() {
        if (!started) return
        started = false
        linkDeathWatchJob?.cancel()
        linkDeathWatchJob = null
        cloudDialJob?.cancel()
        cloudDialJob = null
        lanTcpClient.onLanDialFailed = null
        lanTcpClient.stop()
        lanTcpClient.setTarget(null)
        relayClientProvider()?.closeSession(peerUid)
        lanServer.unregisterClient(peerUid)
        peerLink.clear()
        lanDialFailed = false
        cloudDialFailed = false
    }

    /**
     * TCP health owns failover when LAN sighting is gone. If a fresh LAN beacon is still
     * present, keep dialing LAN — dual-dial races must not lock onto overlay.
     */
    private fun watchLinkDeath() {
        linkDeathWatchJob?.cancel()
        linkDeathWatchJob = scope.launch {
            var lastConnectedVia: String? = null
            peerLink.state.collect { state ->
                when (state) {
                    is PeerLink.LinkState.Connected -> {
                        lastConnectedVia = state.via
                        if (state.via == PathLabels.LOCAL) {
                            lanDialFailed = false
                        }
                        if (state.via == PathLabels.CLOUD) {
                            cloudDialFailed = false
                        }
                    }
                    is PeerLink.LinkState.Disconnected -> {
                        val diedVia = lastConnectedVia
                        lastConnectedVia = null
                        if (diedVia == PathLabels.LOCAL && started) {
                            val lan = lanPeerProvider()
                            if (LocalLinkDeathPolicy.shouldPromoteOverlay(lan?.peer)) {
                                lanDialFailed = true
                                val overlay = overlayPeerProvider()
                                if (overlay != null) {
                                    promoteToOverlay(overlay)
                                } else if (relayClientProvider()?.isReady() == true) {
                                    promoteToCloud()
                                }
                            } else if (lan != null) {
                                lanDialFailed = false
                                lanTcpClient.setTarget(lan)
                            }
                        } else if (diedVia == PathLabels.VPN && started) {
                            if (relayClientProvider()?.isReady() == true) {
                                promoteToCloud()
                            }
                        } else if (diedVia == PathLabels.CLOUD && started) {
                            cloudDialFailed = true
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun updateLanTarget(peer: RoutedPeerTarget?) {
        if (!started) return
        lanTcpClient.setTarget(peer)
    }

    /** Request connection work without bypassing the current target's retry deadline. */
    fun requestDialReconciliation() {
        lanTcpClient.requestDialReconciliation()
        if (started &&
            peerLink.state.value !is PeerLink.LinkState.Connected &&
            relayClientProvider()?.isReady() == true &&
            lanPeerProvider() == null &&
            overlayPeerProvider() == null
        ) {
            promoteToCloud()
        }
    }

    /**
     * Apply discovery result for this peer using [OverlayFailoverPolicy].
     * A healthy session is never torn down solely because UDP presence went quiet.
     */
    fun applyPathTargets(
        lanPeer: RoutedPeerTarget?,
        overlayPeer: RoutedPeerTarget?,
        nowMs: Long = System.currentTimeMillis(),
        cloudAvailable: Boolean = relayClientProvider()?.isReady() == true,
    ) {
        if (!started) return
        val lan = lanPeer?.takeIf { it.route.path == PeerPath.LAN }
        val connected = peerLink.state.value as? PeerLink.LinkState.Connected
        val sessionHealthy = connected != null && (
            lanTcpClient.hasOpenSession() || connected.via == PathLabels.CLOUD
            )
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = lan?.peer,
            hasOverlayEndpoint = overlayPeer != null,
            activeVia = connected?.via,
            sessionHealthy = sessionHealthy,
            lanDialFailed = lanDialFailed,
            hasCloudEndpoint = cloudAvailable && !cloudDialFailed,
        )
        when (decision.action) {
            OverlayFailoverPolicy.PathAction.USE_LAN -> {
                if (lan == null) return
                lanDialFailed = false
                cloudDialJob?.cancel()
                if (connected?.via == PathLabels.VPN || connected?.via == PathLabels.CLOUD) {
                    lanTcpClient.switchToLanMakeBeforeBreak(lan)
                } else {
                    lanTcpClient.setTarget(lan)
                }
            }
            OverlayFailoverPolicy.PathAction.USE_OVERLAY -> {
                if (overlayPeer == null) return
                cloudDialJob?.cancel()
                if (connected?.via == PathLabels.VPN && lanTcpClient.hasOpenSession()) {
                    lanTcpClient.setTarget(overlayPeer, preserveSession = true)
                    return
                }
                if (connected?.via == PathLabels.CLOUD) {
                    // Make-before-break: dial overlay; Hello replaces Cloud transport.
                    lanTcpClient.setTarget(overlayPeer)
                    return
                }
                promoteToOverlay(overlayPeer)
            }
            OverlayFailoverPolicy.PathAction.USE_CLOUD -> {
                if (!cloudAvailable) return
                if (connected?.via == PathLabels.CLOUD) return
                // Stop LAN/VPN dial loops; Cloud Hello attaches via PeerLink.
                if (!lanTcpClient.hasOpenSession()) {
                    lanTcpClient.setTarget(null)
                }
                promoteToCloud()
            }
            OverlayFailoverPolicy.PathAction.KEEP_SESSION -> Unit
            OverlayFailoverPolicy.PathAction.CLEAR -> {
                cloudDialJob?.cancel()
                lanTcpClient.setTarget(null)
                relayClientProvider()?.closeSession(peerUid)
                if (connected != null) {
                    peerLink.markUnreachable()
                }
            }
        }
    }

    /**
     * Switch to overlay without aborting an in-flight dial to the same endpoint every tick.
     * [forceRestart] only when tearing down a non-VPN session to dial overlay.
     */
    fun promoteToOverlay(overlayPeer: RoutedPeerTarget) {
        if (!started) return
        // Intent first so handshake label / policy see VPN, then adopt standby if live.
        lanTcpClient.setTarget(overlayPeer, preserveSession = true)
        if (lanTcpClient.promoteStandby()) return
        if (lanTcpClient.hasOpenSession() && lanTcpClient.activePathLabel() == PathLabels.VPN) {
            return
        }
        if (lanTcpClient.isActivelyConnectingTo(overlayPeer)) {
            return
        }
        if (lanTcpClient.hasOpenSession() && lanTcpClient.activePathLabel() != PathLabels.VPN) {
            lanTcpClient.setTarget(overlayPeer, forceRestart = true)
        } else {
            lanTcpClient.setTarget(overlayPeer)
        }
    }

    fun promoteToCloud() {
        if (!started) return
        val relay = relayClientProvider() ?: return
        if (!relay.isReady()) return
        if (peerLink.state.value is PeerLink.LinkState.Connected &&
            (peerLink.state.value as PeerLink.LinkState.Connected).via == PathLabels.CLOUD
        ) {
            return
        }
        if (cloudDialJob?.isActive == true) return
        cloudDialJob = scope.launch {
            val ok = relay.dial(peerUid)
            if (!ok) {
                cloudDialFailed = true
                Log.i(TAG, "cloud dial failed for $peerUid")
                kotlinx.coroutines.delay(5_000L)
                cloudDialFailed = false
                return@launch
            }
            cloudDialFailed = false
            val transport = relay.transportFor(peerUid)
            transport.attach(peerLink)
            transport.startHandshake(peerLink)
        }
    }

    /** Accept an inbound cloud bridge (peer dialed us on the Mini). */
    fun acceptCloudInbound() {
        if (!started) return
        val relay = relayClientProvider() ?: return
        val transport = relay.transportFor(peerUid)
        transport.attach(peerLink)
        transport.startHandshake(peerLink)
    }

    /** LAN sighting expired — switch to overlay if available; never kill healthy overlay TCP. */
    fun onLanPeerAbsent(overlayPeer: RoutedPeerTarget? = null) {
        if (!started) return
        applyPathTargets(lanPeer = null, overlayPeer = overlayPeer)
    }

    fun send(payload: ByteArray) {
        if (!started) return
        peerLink.send(payload)
    }

    fun sendMediaActivity(active: Boolean) {
        if (!started) return
        peerLink.sendMediaActivity(active)
    }

    /** Invalidate only sessions attached to exact networks that disappeared or changed IPv4. */
    fun onNetworksInvalidated(networkHandles: Set<Long>) {
        if (!started) return
        val invalidation = lanTcpClient.onNetworksInvalidated(networkHandles)
        if (invalidation.lostSessionPath != null) {
            peerLink.onDisconnected(lanTcpClient, peerUid)
        }
        when (invalidation.affectedPath) {
            PeerPath.LAN -> {
                lanDialFailed = true
                val overlay = overlayPeerProvider()
                if (overlay != null) {
                    logInfo("LAN_INVALIDATED peer=$peerUid fallback=overlay host=${overlay.peer.host}:${overlay.peer.port}")
                    promoteToOverlay(overlay)
                } else if (relayClientProvider()?.isReady() == true) {
                    logInfo("LAN_INVALIDATED peer=$peerUid fallback=cloud")
                    promoteToCloud()
                }
            }
            PeerPath.OVERLAY -> {
                lanDialFailed = false
                val lan = lanPeerProvider()
                if (lan != null) {
                    logInfo(
                        "OVERLAY_INVALIDATED peer=$peerUid fallback=lan " +
                            "network=${lan.route.networkHandle} host=${lan.peer.host}:${lan.peer.port}",
                    )
                    lanTcpClient.setTarget(lan)
                } else if (relayClientProvider()?.isReady() == true) {
                    logInfo("OVERLAY_INVALIDATED peer=$peerUid fallback=cloud")
                    promoteToCloud()
                } else {
                    logInfo("OVERLAY_INVALIDATED peer=$peerUid fallback=none")
                }
            }
            null -> Unit
        }
    }

    /** Test/observe: whether LAN dials are currently suppressed in favour of overlay. */
    internal fun lanDialFailedForTest(): Boolean = lanDialFailed

    internal fun targetPathForTest(): PeerPath? = lanTcpClient.targetPathForTest()

    @Suppress("UNUSED")
    fun isWanted(): Boolean = isStillWanted()

    private companion object {
        const val TAG = "PeerConnection"

        fun logInfo(message: String) {
            runCatching { Log.i(TAG, message) }
        }
    }
}

data class PeerMetrics(
    val rttMs: Long? = null,
    val pathLabel: String? = null,
    val backlogMs: Long = 0L,
    val linkState: PeerLink.LinkState = PeerLink.LinkState.Idle,
)
