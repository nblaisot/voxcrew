package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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
) {
    val peerLink = PeerLink(scope)
    private val lanTcpClient = LanTcpClient(scope, localUid, peerLink, lanServer)

    val linkState: StateFlow<PeerLink.LinkState> = peerLink.state
    val rttMs: StateFlow<Long?> = peerLink.rttMs
    val backlogMs: StateFlow<Long> = peerLink.backlogMs

    private var started = false

    fun start() {
        if (started) return
        started = true
        lanServer.registerClient(peerUid, lanTcpClient)
        peerLink.resetFor(peerUid)
    }

    fun stop() {
        if (!started) return
        started = false
        lanTcpClient.stop()
        lanTcpClient.setTarget(null)
        lanServer.unregisterClient(peerUid)
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
                onPeerPresenceLost()
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
        onPeerPresenceLost()
    }

    /** Peer no longer discoverable — tear down any active audio link. */
    fun onPeerPresenceLost() {
        if (!started) return
        if ((peerLink.state.value as? PeerLink.LinkState.Connected)?.via == lanTcpClient.label) {
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

    fun onNetworkChanged() {
        // LAN/overlay targets are refreshed by the engine's beacon restart.
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
