package com.nblaisot.voxcrew.lanlink

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
    private val isStillWanted: () -> Boolean,
    private val overlayPeerProvider: () -> LanPeer? = { null },
) {
    val peerLink = PeerLink(scope)
    private val lanTcpClient = LanTcpClient(scope, localUid, peerLink, lanServer)

    val linkState: StateFlow<PeerLink.LinkState> = peerLink.state
    val rttMs: StateFlow<Long?> = peerLink.rttMs
    val backlogMs: StateFlow<Long> = peerLink.backlogMs

    private var started = false
    private var linkDeathWatchJob: Job? = null

    fun start() {
        if (started) return
        started = true
        lanServer.registerClient(peerUid, lanTcpClient)
        peerLink.resetFor(peerUid)
        watchLinkDeath()
    }

    fun stop() {
        if (!started) return
        started = false
        linkDeathWatchJob?.cancel()
        linkDeathWatchJob = null
        lanTcpClient.stop()
        lanTcpClient.setTarget(null)
        lanServer.unregisterClient(peerUid)
        peerLink.clear()
    }

    /**
     * TCP health owns failover: a LAN link dying (frame-activity timeout or socket close)
     * promotes overlay immediately instead of waiting for the beacon sighting to go stale.
     */
    private fun watchLinkDeath() {
        linkDeathWatchJob?.cancel()
        linkDeathWatchJob = scope.launch {
            var lastConnectedVia: String? = null
            peerLink.state.collect { state ->
                when (state) {
                    is PeerLink.LinkState.Connected -> lastConnectedVia = state.via
                    is PeerLink.LinkState.Disconnected -> {
                        val diedVia = lastConnectedVia
                        lastConnectedVia = null
                        if (diedVia == PathLabels.LOCAL && started) {
                            overlayPeerProvider()?.let { promoteToOverlay(it) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun updateLanTarget(peer: LanPeer?) {
        if (!started) return
        lanTcpClient.setTarget(peer)
    }

    /** User action (PTT, recipient toggle) grants an immediate dial retry despite backoff. */
    fun resetDialBackoff() {
        lanTcpClient.resetDialBackoff()
    }

    /**
     * Apply discovery result for this peer using [OverlayFailoverPolicy].
     * A healthy TCP session is never torn down solely because UDP presence went quiet.
     */
    fun applyPathTargets(
        lanPeer: LanPeer?,
        overlayPeer: LanPeer?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!started) return
        val lan = lanPeer?.takeUnless { it.viaOverlay }
        val connected = peerLink.state.value as? PeerLink.LinkState.Connected
        val sessionHealthy = connected != null && lanTcpClient.hasOpenSession()
        val decision = OverlayFailoverPolicy.decide(
            lanSighting = lan,
            hasOverlayEndpoint = overlayPeer != null,
            nowMs = nowMs,
            activeVia = connected?.via,
            sessionHealthy = sessionHealthy,
        )
        when (decision.action) {
            OverlayFailoverPolicy.PathAction.USE_LAN -> {
                if (lan == null) return
                if (decision.warmStandby && overlayPeer != null) {
                    lanTcpClient.warmStandby(overlayPeer)
                } else {
                    lanTcpClient.clearStandby()
                }
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
            OverlayFailoverPolicy.PathAction.KEEP_SESSION -> {
                // Beacon quiet but LAN TCP healthy: prime overlay so link-death promote is instant.
                if (decision.warmStandby && overlayPeer != null) {
                    lanTcpClient.warmStandby(overlayPeer)
                }
            }
            OverlayFailoverPolicy.PathAction.CLEAR -> {
                lanTcpClient.setTarget(null)
                if (connected != null) {
                    peerLink.markUnreachable()
                }
            }
        }
    }

    fun promoteToOverlay(overlayPeer: LanPeer) {
        if (!started) return
        // Intent first so handshake label / policy see VPN, then adopt standby if live.
        lanTcpClient.setTarget(overlayPeer, preserveSession = true)
        if (lanTcpClient.promoteStandby()) return
        if (!lanTcpClient.hasOpenSession() || lanTcpClient.activePathLabel() != PathLabels.VPN) {
            lanTcpClient.setTarget(overlayPeer, forceRestart = true)
        }
    }

    /** LAN sighting expired — switch to overlay if available; never kill healthy overlay TCP. */
    fun onLanPeerAbsent(overlayPeer: LanPeer? = null) {
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

    fun onNetworkChanged() {
        // Path targets refresh from presence; beacon rebinds without wiping registries.
    }

    @Suppress("UNUSED")
    fun isWanted(): Boolean = isStillWanted()
}

data class PeerMetrics(
    val rttMs: Long? = null,
    val pathLabel: String? = null,
    val backlogMs: Long = 0L,
    val linkState: PeerLink.LinkState = PeerLink.LinkState.Idle,
)
