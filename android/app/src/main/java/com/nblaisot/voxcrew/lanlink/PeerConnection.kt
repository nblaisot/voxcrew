package com.nblaisot.voxcrew.lanlink

import android.util.Log
import com.nblaisot.voxcrew.connectivity.NetworkSocketBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One intercom link to a single peer: own [PeerLink] and LAN TCP client, with optional
 * Tailscale overlay failover.
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
    private val onOverlayEndpointDead: (String) -> Unit = {},
) {
    val peerLink = PeerLink(scope)
    private val lanTcpClient = LanTcpClient(
        scope,
        localUid,
        peerLink,
        lanServer,
        networkSocketBinder,
        inboundRouteResolver,
    )

    val linkState: StateFlow<PeerLink.LinkState> = peerLink.state
    val rttMs: StateFlow<Long?> = peerLink.rttMs
    val backlogMs: StateFlow<Long> = peerLink.backlogMs

    private var started = false
    private var linkDeathWatchJob: Job? = null
    /** After a *real* LAN dial failure, prefer overlay until a network reset or LAN Connected. */
    @Volatile private var lanDialFailed = false

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
                }
            }
        }
        lanTcpClient.onOverlayEndpointDead = { uid ->
            if (started) onOverlayEndpointDead(uid)
        }
        watchLinkDeath()
    }

    fun stop() {
        if (!started) return
        started = false
        linkDeathWatchJob?.cancel()
        linkDeathWatchJob = null
        lanTcpClient.onLanDialFailed = null
        lanTcpClient.onOverlayEndpointDead = null
        lanTcpClient.stop()
        lanTcpClient.setTarget(null)
        lanServer.unregisterClient(peerUid)
        peerLink.clear()
        lanDialFailed = false
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
                    }
                    is PeerLink.LinkState.Disconnected -> {
                        val diedVia = lastConnectedVia
                        lastConnectedVia = null
                        if (diedVia == PathLabels.LOCAL && started) {
                            val lan = lanPeerProvider()
                            if (LocalLinkDeathPolicy.shouldPromoteOverlay(lan?.peer)) {
                                lanDialFailed = true
                                overlayPeerProvider()?.let { promoteToOverlay(it) }
                            } else if (lan != null) {
                                lanDialFailed = false
                                lanTcpClient.setTarget(lan)
                            }
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
    }

    /**
     * Apply discovery result for this peer using [OverlayFailoverPolicy].
     * A healthy TCP session is never torn down solely because UDP presence went quiet.
     */
    fun applyPathTargets(
        lanPeer: RoutedPeerTarget?,
        overlayPeer: RoutedPeerTarget?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!started) return
        val lan = lanPeer?.takeIf { it.route.path == PeerPath.LAN }
        val connected = peerLink.state.value as? PeerLink.LinkState.Connected
        val sessionHealthy = connected != null && lanTcpClient.hasOpenSession()
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = lan?.peer,
            hasOverlayEndpoint = overlayPeer != null,
            activeVia = connected?.via,
            sessionHealthy = sessionHealthy,
            lanDialFailed = lanDialFailed,
        )
        when (decision.action) {
            OverlayFailoverPolicy.PathAction.USE_LAN -> {
                if (lan == null) return
                lanDialFailed = false
                if (connected?.via == PathLabels.VPN) {
                    lanTcpClient.switchToLanMakeBeforeBreak(lan)
                } else {
                    lanTcpClient.setTarget(lan)
                }
            }
            OverlayFailoverPolicy.PathAction.USE_OVERLAY -> {
                if (overlayPeer == null) return
                if (connected?.via == PathLabels.VPN && lanTcpClient.hasOpenSession()) {
                    lanTcpClient.setTarget(overlayPeer, preserveSession = true)
                    return
                }
                promoteToOverlay(overlayPeer)
            }
            OverlayFailoverPolicy.PathAction.KEEP_SESSION -> Unit
            OverlayFailoverPolicy.PathAction.CLEAR -> {
                lanTcpClient.setTarget(null)
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
                overlayPeerProvider()?.let {
                    logInfo("LAN_INVALIDATED peer=$peerUid fallback=overlay host=${it.peer.host}:${it.peer.port}")
                    promoteToOverlay(it)
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
