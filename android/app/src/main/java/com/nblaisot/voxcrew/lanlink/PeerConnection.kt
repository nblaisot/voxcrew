package com.nblaisot.voxcrew.lanlink

import android.util.Log
import com.nblaisot.voxcrew.connectivity.NetworkSocketBinder
import com.nblaisot.voxcrew.relay.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** In-flight [relay.dial] + handshake only — never a sleep-only cooldown job. */
    private var cloudDialJob: Job? = null
    /** Light poll while parked after dial_fail / Cloud death awaiting [roster_match]-style wake. */
    private var cloudSafetyJob: Job? = null
    /** After a *real* LAN dial failure, prefer overlay/cloud until a network reset or LAN Connected. */
    @Volatile private var lanDialFailed = false
    /**
     * After unsuccessful Cloud dial: unforced [promoteToCloud] / USE_CLOUD ticks no-op until
     * an event forces dial ([force]=true) or the 3s safety net runs.
     */
    @Volatile private var cloudAwaitMatch = false
    /**
     * [roster_match] / path-failover asked for a force dial while one was already in flight.
     * Retried immediately if that dial fails (do not drop the event).
     */
    @Volatile private var pendingForceCloudDial = false
    /** Host|handle of the last LAN target — identity change clears [lanDialFailed]. */
    @Volatile private var lastLanRouteKey: String? = null
    @Volatile private var connectingSinceMs: Long? = null
    private val clockMs: () -> Long = System::currentTimeMillis

    fun start() {
        if (started) return
        started = true
        lanServer.registerClient(peerUid, lanTcpClient)
        peerLink.resetFor(peerUid)
        lanTcpClient.onLanDialFailed = {
            if (started) {
                lanDialFailed = true
                // Local > Cloud > VPN: prefer Cloud when relay is ready.
                if (relayClientProvider()?.isReady() == true) {
                    logInfo("LAN dial failed for $peerUid; switching to cloud")
                    promoteToCloud(force = true)
                } else {
                    val overlay = overlayPeerProvider()
                    if (overlay != null) {
                        logInfo(
                            "LAN dial failed for $peerUid; switching to overlay ${overlay.peer.host}",
                        )
                        promoteToOverlay(overlay)
                    }
                }
            }
        }
        lanTcpClient.onOverlayDialFailed = {
            if (started && relayClientProvider()?.isReady() == true) {
                logInfo("overlay dial failed for $peerUid; switching to cloud")
                promoteToCloud(force = true)
            }
        }
        watchLinkDeath()
    }

    fun stop() {
        if (!started) return
        started = false
        linkDeathWatchJob?.cancel()
        linkDeathWatchJob = null
        stopCloudJobs()
        lanTcpClient.onLanDialFailed = null
        lanTcpClient.onOverlayDialFailed = null
        lanTcpClient.stop()
        lanTcpClient.setTarget(null)
        relayClientProvider()?.closeSession(peerUid)
        lanServer.unregisterClient(peerUid)
        peerLink.clear()
        lanDialFailed = false
        cloudAwaitMatch = false
        pendingForceCloudDial = false
        lastLanRouteKey = null
        connectingSinceMs = null
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
                        connectingSinceMs = null
                        lastConnectedVia = state.via
                        if (state.via == PathLabels.LOCAL) {
                            lanDialFailed = false
                            // Local lock: abandon Cloud dials/sessions so they cannot steal PeerLink.
                            lockLocalMediaPipe()
                        }
                        if (state.via == PathLabels.CLOUD) {
                            cloudAwaitMatch = false
                            pendingForceCloudDial = false
                            stopCloudSafetyJob()
                        }
                    }
                    is PeerLink.LinkState.Connecting -> {
                        if (connectingSinceMs == null) connectingSinceMs = clockMs()
                    }
                    is PeerLink.LinkState.Disconnected -> {
                        connectingSinceMs = null
                        val diedVia = lastConnectedVia
                        lastConnectedVia = null
                        if (diedVia == PathLabels.LOCAL && started) {
                            val lan = lanPeerProvider()
                            if (LocalLinkDeathPolicy.shouldPromoteOverlay(lan?.peer)) {
                                lanDialFailed = true
                                if (relayClientProvider()?.isReady() == true) {
                                    promoteToCloud(force = true)
                                } else {
                                    val overlay = overlayPeerProvider()
                                    if (overlay != null) {
                                        promoteToOverlay(overlay)
                                    }
                                }
                            } else if (lan != null) {
                                lanDialFailed = false
                                lanTcpClient.setTarget(lan)
                            }
                        } else if (diedVia == PathLabels.VPN && started) {
                            if (relayClientProvider()?.isReady() == true) {
                                promoteToCloud(force = true)
                            }
                        } else if (diedVia == PathLabels.CLOUD && started) {
                            // Immediate force dial; on fail park for roster_match / safety net.
                            promoteToCloud(force = true)
                        }
                    }
                    PeerLink.LinkState.Idle -> {
                        connectingSinceMs = null
                    }
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
        val lanKey = lan?.let { "${it.peer.host}|${it.route.networkHandle}" }
        if (lanKey != null && lanKey != lastLanRouteKey) {
            // Fresh LAN route (new host or Network handle after eviction) — retry Local.
            lanDialFailed = false
            lastLanRouteKey = lanKey
        } else if (lanKey == null) {
            lastLanRouteKey = null
        }
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
            hasCloudEndpoint = cloudAvailable,
        )
        when (decision.action) {
            OverlayFailoverPolicy.PathAction.USE_LAN -> {
                if (lan == null) return
                lanDialFailed = false
                stopCloudJobs()
                if (connected?.via == PathLabels.VPN || connected?.via == PathLabels.CLOUD) {
                    lanTcpClient.switchToLanMakeBeforeBreak(lan)
                } else {
                    lanTcpClient.setTarget(lan)
                }
            }
            OverlayFailoverPolicy.PathAction.USE_OVERLAY -> {
                if (overlayPeer == null) return
                stopCloudJobs()
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
                // Path lock: never Cloud while Seeking Local (LAN sighting / Local TCP).
                if (cloudBlockedByLanPreference()) return
                // Stop LAN/VPN dial loops; Cloud Hello attaches via PeerLink.
                if (!lanTcpClient.hasOpenSession()) {
                    lanTcpClient.setTarget(null)
                }
                promoteToCloud(force = false)
            }
            OverlayFailoverPolicy.PathAction.KEEP_SESSION -> {
                if (connected?.via == PathLabels.LOCAL) {
                    lockLocalMediaPipe()
                }
            }
            OverlayFailoverPolicy.PathAction.CLEAR -> {
                // Real teardown (no Cloud available). Do not leave sticky that blocks future Cloud.
                stopCloudJobs()
                cloudAwaitMatch = false
                pendingForceCloudDial = false
                lanTcpClient.setTarget(null)
                relayClientProvider()?.closeSession(peerUid)
                if (connected != null) {
                    peerLink.markUnreachable()
                }
            }
        }
    }

    /**
     * If we have been [PeerLink.LinkState.Connecting] longer than [CONNECTING_STALL_MS],
     * clear dial suppression and force a new dial generation.
     */
    fun maybeRecoverStuckConnecting(nowMs: Long = clockMs()): Boolean {
        if (!started) return false
        if (peerLink.state.value !is PeerLink.LinkState.Connecting) return false
        val since = connectingSinceMs ?: return false
        if (nowMs - since < CONNECTING_STALL_MS) return false
        Log.i(TAG, "connecting stall peer=$peerUid after ${nowMs - since}ms; forcing redial")
        connectingSinceMs = nowMs
        lanDialFailed = false
        val lan = lanPeerProvider()
        if (lan != null) {
            lanTcpClient.setTarget(lan, forceRestart = true)
            return true
        }
        val overlay = overlayPeerProvider()
        if (overlay != null) {
            lanTcpClient.setTarget(overlay, forceRestart = true)
            return true
        }
        if (relayClientProvider()?.isReady() == true) {
            promoteToCloud(force = true)
            return true
        }
        return false
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

    /**
     * Dial peer UUID via the relay.
     * @param force true for event wakes (roster_match, path death/failover); false for
     *   periodic USE_CLOUD ticks that must respect [cloudAwaitMatch] after dial_fail.
     *
     * If [force] arrives while a dial is already in flight, we do **not** cancel mid-dial
     * (avoids dialWaiter races). Instead [pendingForceCloudDial] retries immediately if
     * that dial fails — so roster_match is never a silent no-op.
     */
    fun promoteToCloud(force: Boolean = false) {
        if (!started) return
        val relay = relayClientProvider() ?: return
        if (!relay.isReady()) return
        // Path lock: Local preference blocks Cloud while LAN is in play or Local is up.
        if (cloudBlockedByLanPreference()) {
            pendingForceCloudDial = false
            cloudAwaitMatch = false
            logInfo("cloud dial skip peer=$peerUid reason=lan_preferred force=$force")
            return
        }
        if (isConnectedVia(PathLabels.CLOUD)) {
            pendingForceCloudDial = false
            return
        }
        if (cloudDialJob?.isActive == true) {
            if (force) {
                pendingForceCloudDial = true
                logInfo("cloud dial pending force peer=$peerUid (in_flight)")
            } else {
                logInfo("cloud dial skip peer=$peerUid reason=in_flight force=false")
            }
            return
        }
        if (cloudAwaitMatch && !force) {
            logInfo("cloud dial skip peer=$peerUid reason=await_match")
            return
        }
        if (force) {
            cloudAwaitMatch = false
            pendingForceCloudDial = false
        }
        peerLink.markConnecting(peerUid)
        logInfo("cloud dial start peer=$peerUid force=$force")
        cloudDialJob = scope.launch {
            val ok = relay.dial(peerUid)
            cloudDialJob = null
            if (!ok) {
                logInfo("cloud dial failed for $peerUid")
                if (cloudBlockedByLanPreference()) {
                    pendingForceCloudDial = false
                    cloudAwaitMatch = false
                    return@launch
                }
                cloudAwaitMatch = true
                val retryForce = pendingForceCloudDial
                pendingForceCloudDial = false
                if (retryForce && started && isStillWanted() &&
                    relayClientProvider()?.isReady() == true &&
                    peerLink.state.value !is PeerLink.LinkState.Connected &&
                    !cloudBlockedByLanPreference()
                ) {
                    logInfo("cloud dial retry after pending force peer=$peerUid")
                    promoteToCloud(force = true)
                    return@launch
                }
                ensureCloudSafetyNet()
                return@launch
            }
            pendingForceCloudDial = false
            cloudAwaitMatch = false
            stopCloudSafetyJob()
            // Dial raced Local: discard Cloud attach so audio stays on the LAN mesh.
            if (cloudBlockedByLanPreference()) {
                logInfo("cloud dial discarded after ok; Local preferred peer=$peerUid")
                relayClientProvider()?.closeSession(peerUid)
                return@launch
            }
            val transport = relay.transportFor(peerUid)
            transport.attach(peerLink)
            transport.startHandshake(peerLink)
        }
    }

    /** Accept an inbound cloud bridge (peer dialed us on the Mini). */
    fun acceptCloudInbound() {
        if (!started) return
        if (cloudBlockedByLanPreference()) {
            logInfo("ignore inbound cloud; lan preferred peer=$peerUid")
            relayClientProvider()?.closeSession(peerUid)
            return
        }
        val relay = relayClientProvider() ?: return
        cloudAwaitMatch = false
        pendingForceCloudDial = false
        stopCloudSafetyJob()
        peerLink.markConnecting(peerUid)
        val transport = relay.transportFor(peerUid)
        transport.attach(peerLink)
        transport.startHandshake(peerLink)
    }

    /**
     * True while Local should own media: Locked Local, open LAN TCP, or Seeking with a
     * LAN beacon. Blocks Cloud dials so icon and audio stay on one pipe.
     */
    private fun cloudBlockedByLanPreference(): Boolean {
        if (isConnectedVia(PathLabels.LOCAL)) return true
        if (lanTcpClient.hasHealthyLocalSession()) return true
        if (lanPeerProvider() != null) return true
        return false
    }

    private fun isConnectedVia(via: String): Boolean {
        val connected = peerLink.state.value as? PeerLink.LinkState.Connected ?: return false
        return connected.via == via
    }

    /** Cancel Cloud work and drop any relay peer session — Local is the media path. */
    private fun lockLocalMediaPipe() {
        stopCloudJobs()
        cloudAwaitMatch = false
        relayClientProvider()?.closeSession(peerUid)
    }

    private fun ensureCloudSafetyNet() {
        if (cloudSafetyJob?.isActive == true) return
        cloudSafetyJob = scope.launch {
            while (started && isStillWanted() && cloudAwaitMatch) {
                delay(CLOUD_SAFETY_INTERVAL_MS)
                if (!started || !isStillWanted() || !cloudAwaitMatch) return@launch
                if (peerLink.state.value is PeerLink.LinkState.Connected) {
                    cloudAwaitMatch = false
                    return@launch
                }
                if (cloudBlockedByLanPreference()) {
                    cloudAwaitMatch = false
                    return@launch
                }
                if (relayClientProvider()?.isReady() != true) continue
                logInfo("cloud safety dial peer=$peerUid")
                promoteToCloud(force = true)
            }
        }
    }

    private fun stopCloudSafetyJob() {
        cloudSafetyJob?.cancel()
        cloudSafetyJob = null
    }

    private fun stopCloudJobs() {
        cloudDialJob?.cancel()
        cloudDialJob = null
        pendingForceCloudDial = false
        stopCloudSafetyJob()
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
                // Stale Network handle (EPERM) or Wi‑Fi loss: prefer a fresh Local route
                // (including SoftAP unbound) before locking onto overlay.
                lanDialFailed = false
                lastLanRouteKey = null
                val lan = lanPeerProvider()
                if (lan != null) {
                    logInfo(
                        "LAN_INVALIDATED peer=$peerUid fallback=lan " +
                            "network=${lan.route.networkHandle} host=${lan.peer.host}:${lan.peer.port}",
                    )
                    lanTcpClient.setTarget(lan, forceRestart = true)
                } else {
                    val overlay = overlayPeerProvider()
                    if (overlay != null) {
                        lanDialFailed = true
                        logInfo("LAN_INVALIDATED peer=$peerUid fallback=overlay host=${overlay.peer.host}:${overlay.peer.port}")
                        promoteToOverlay(overlay)
                    } else if (relayClientProvider()?.isReady() == true) {
                        lanDialFailed = true
                        logInfo("LAN_INVALIDATED peer=$peerUid fallback=cloud")
                        promoteToCloud(force = true)
                    }
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
                    promoteToCloud(force = true)
                } else {
                    logInfo("OVERLAY_INVALIDATED peer=$peerUid fallback=none")
                }
            }
            null -> Unit
        }
    }

    /** Test/observe: whether LAN dials are currently suppressed in favour of overlay. */
    internal fun lanDialFailedForTest(): Boolean = lanDialFailed

    /** Test/observe: parked after dial_fail awaiting event / safety net. */
    internal fun cloudAwaitMatchForTest(): Boolean = cloudAwaitMatch

    /** Test/observe: force dial queued while another dial is in flight. */
    internal fun pendingForceCloudDialForTest(): Boolean = pendingForceCloudDial

    internal fun targetPathForTest(): PeerPath? = lanTcpClient.targetPathForTest()

    @Suppress("UNUSED")
    fun isWanted(): Boolean = isStillWanted()

    private companion object {
        const val TAG = "PeerConnection"
        /** Force a redial if we sit in Connecting without reaching Connected. */
        internal const val CONNECTING_STALL_MS = 15_000L
        /** While [cloudAwaitMatch], force-dial at this interval until Connected or stop. */
        internal const val CLOUD_SAFETY_INTERVAL_MS = 3_000L

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
