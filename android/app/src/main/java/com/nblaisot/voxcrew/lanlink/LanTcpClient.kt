package com.nblaisot.voxcrew.lanlink

import android.util.Log
import com.nblaisot.voxcrew.connectivity.NetworkSocketBinder
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
 * Per-peer LAN/overlay [FrameTransport]: outbound dial and one TCP session bound to a
 * single [PeerLink]. Inbound accepts are handed off by [LanTcpServer].
 *
 * Both peers dial when a target is set; the first successful Hello wins.
 * [label] reflects the **live socket** path after Hello/adopt, not dial intent alone.
 */
class LanTcpClient(
    private val scope: CoroutineScope,
    private val localUid: String,
    private val peerLink: PeerLink,
    private val server: LanTcpServer,
    private val networkSocketBinder: NetworkSocketBinder,
    private val inboundRouteResolver: (Socket) -> RoutedSocketPath?,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val socketFactory: () -> Socket = ::Socket,
    private val relayOfferProvider: () -> com.nblaisot.voxcrew.relay.RelayConfigLink? = { null },
) : FrameTransport {
    @Volatile private var sessionRoute: RoutedSocketPath? = null
    @Volatile private var sessionDirection: SessionDirection? = null

    override val label: String
        get() = if (session?.closed == false) {
            sessionRoute?.label ?: PathLabels.LOCAL
        } else {
            targetPeer?.route?.label ?: PathLabels.LOCAL
        }

    private val peerUid: String get() = peerLink.selectedPeerUid.orEmpty()

    @Volatile private var targetPeer: RoutedPeerTarget? = null
    private var connectJob: Job? = null
    private var session: LanTcpSession? = null
    @Volatile private var connectingSocket: Socket? = null
    /** At most one lower-priority overlay socket may be parked behind a healthy LAN. */
    @Volatile private var standby: ParkedSession? = null
    @Volatile private var lastTargetIdentity: String? = null
    /** Bumped on intentional cancel so in-flight dials do not count as failures. */
    @Volatile private var cancelGeneration = 0
    private var nextSessionGeneration = 0L
    private var nextDialGeneration = 0L
    private var activeDialGeneration = 0L
    private val retryStates = mutableMapOf<String, RetryState>()

    /** Fired after a *real* failed dial to a non-overlay (LAN) target (not intentional cancel). */
    @Volatile var onLanDialFailed: (() -> Unit)? = null

    /** Peer offered crew relay settings on a Local Hello (UI may confirm). */
    @Volatile var onRelayOffer: ((peerUid: String, offer: com.nblaisot.voxcrew.relay.RelayConfigLink) -> Unit)? = null

    fun lastContiguousInSeq(): Long = peerLink.lastContiguousInSeq()

    /** Local-path Hello only: optional relay config to piggyback. */
    fun relayOfferForLanHello(): com.nblaisot.voxcrew.relay.RelayConfigLink? = relayOfferProvider()

    fun relayOfferIfLanInbound(socket: Socket): com.nblaisot.voxcrew.relay.RelayConfigLink? {
        val route = inboundRouteResolver(socket) ?: return null
        if (route.path != PeerPath.LAN) return null
        return relayOfferProvider()
    }

    fun hasOpenSession(): Boolean {
        val s = session
        return s != null && !s.closed
    }

    fun activePathLabel(): String? =
        session?.takeIf { !it.closed }?.let { sessionRoute?.label }

    fun hasHealthyLocalSession(): Boolean {
        val s = session
        return s != null && !s.closed && sessionRoute?.path == PeerPath.LAN
    }

    /** True while connectLoop / make-before-break is dialing this exact endpoint. */
    fun isActivelyConnectingTo(candidate: RoutedPeerTarget): Boolean {
        val target = targetPeer ?: return false
        if (connectJob?.isActive != true) return false
        return targetIdentity(target) == targetIdentity(candidate)
    }

    /** Sets (or clears) which peer this client dials. Host changes force a re-dial. */
    fun setTarget(
        target: RoutedPeerTarget?,
        forceRestart: Boolean = false,
        preserveSession: Boolean = false,
    ) {
        val closeActions = mutableListOf<() -> Unit>()
        var disconnected = false
        synchronized(this) {
            val previousIdentity = targetPeer?.let(::targetIdentity)
            targetPeer = target
            if (target == null) {
                detachConnectInFlightLocked(closeActions)
                cancelDialJobLocked()
                standby?.let { parked -> closeActions += { parked.close() } }
                standby = null
                session?.let { current ->
                    closeActions += { current.close() }
                    disconnected = true
                }
                session = null
                sessionRoute = null
                sessionDirection = null
                lastTargetIdentity = null
                return@synchronized
            }

            val identity = targetIdentity(target)
            val identityChanged = previousIdentity != null && identity != previousIdentity
            lastTargetIdentity = identity
            if (identityChanged && connectJob?.isActive == true) {
                detachConnectInFlightLocked(closeActions)
                cancelDialJobLocked()
            }
            val mustRestart = !preserveSession && (forceRestart || identityChanged)
            if (mustRestart) {
                session?.let { current ->
                    closeActions += { current.close() }
                    disconnected = true
                }
                session = null
                sessionRoute = null
                sessionDirection = null
            }
            ensureDialingLocked()
        }
        if (disconnected) peerLink.onDisconnected(this, peerUid)
        DetachedNetworkResources(closeActions).closeAsync(scope, TAG)
    }

    /** Reconciliation request; repeated user actions never bypass the target deadline. */
    fun requestDialReconciliation() {
        synchronized(this) { ensureDialingLocked() }
    }

    internal fun recordDialFailureForTest(target: RoutedPeerTarget? = targetPeer) {
        synchronized(this) {
            target?.let(::recordFailureLocked)
        }
    }

    internal fun confirmTargetForTest(target: RoutedPeerTarget) {
        synchronized(this) { retryStates.remove(targetIdentity(target)) }
    }

    internal fun failureCountForTest(target: RoutedPeerTarget): Int =
        synchronized(this) { retryStates[targetIdentity(target)]?.failures ?: 0 }

    internal fun nextEligibleAtForTest(target: RoutedPeerTarget): Long =
        synchronized(this) { retryStates[targetIdentity(target)]?.nextEligibleAtMs ?: 0L }

    internal fun cancelGenerationForTest(): Int = cancelGeneration

    internal fun bumpCancelGenerationForTest() {
        cancelGeneration++
    }

    internal fun isCancelledAttempt(generationAtStart: Int): Boolean =
        generationAtStart != cancelGeneration

    /** Detach only sessions and targets routed through networks that actually disappeared. */
    internal fun onNetworksInvalidated(networkHandles: Set<Long>): NetworkInvalidationResult {
        if (networkHandles.isEmpty()) return NetworkInvalidationResult()
        val result = synchronized(this) {
            val closeActions = mutableListOf<() -> Unit>()
            var affectedPath: PeerPath? = null
            var lostSessionPath: PeerPath? = null

            standby?.takeIf { it.route.networkHandle in networkHandles }?.let {
                closeActions += { it.close() }
                standby = null
                affectedPath = affectedPath ?: it.route.path
            }
            targetPeer?.takeIf { it.route.networkHandle in networkHandles }?.let {
                affectedPath = affectedPath ?: it.route.path
                detachConnectInFlightLocked(closeActions)
                cancelDialJobLocked()
                targetPeer = null
                lastTargetIdentity = null
            }
            sessionRoute?.takeIf { it.networkHandle in networkHandles }?.let { route ->
                affectedPath = affectedPath ?: route.path
                lostSessionPath = route.path
                detachConnectInFlightLocked(closeActions)
                cancelDialJobLocked()
                session?.let { closeActions += { it.close() } }
                session = null
                sessionRoute = null
                sessionDirection = null
            }

            NetworkInvalidationResult(
                affectedPath = affectedPath,
                lostSessionPath = lostSessionPath,
                detachedResources = DetachedNetworkResources(closeActions),
            )
        }
        result.detachedResources.closeAsync(scope, TAG)
        return result
    }

    @Synchronized
    internal fun targetPathForTest(): PeerPath? = targetPeer?.route?.path

    private fun detachConnectInFlightLocked(closeActions: MutableList<() -> Unit>) {
        cancelGeneration++
        connectingSocket?.let { socket -> closeActions += { socket.close() } }
        connectingSocket = null
    }

    fun clearStandby() {
        val parked = synchronized(this) {
            standby.also { standby = null }
        }
        parked?.let { DetachedNetworkResources(listOf({ it.close() })).closeAsync(scope, TAG) }
    }

    /**
     * Promote a parked standby session into the active PeerLink path, if still open.
     * Caller should [setTarget] overlay with preserveSession first so [label] intent matches.
     */
    fun promoteStandby(): Boolean {
        val closeActions = mutableListOf<() -> Unit>()
        val parked = synchronized(this) {
            val candidate = standby
            standby = null
            if (candidate?.isOpen() != true) {
                candidate?.let { closeActions += { it.close() } }
                null
            } else {
                detachConnectInFlightLocked(closeActions)
                cancelDialJobLocked()
                candidate
            }
        }
        DetachedNetworkResources(closeActions).closeAsync(scope, TAG)
        if (parked == null) return false
        adoptParkedSession(parked)
        return true
    }

    /**
     * Dial [lanPeer] without dropping the current overlay session until Hello succeeds
     * (make-before-break back to LAN).
     */
    fun switchToLanMakeBeforeBreak(lanPeer: RoutedPeerTarget) {
        if (lanPeer.route.path != PeerPath.LAN) return
        val closeActions = mutableListOf<() -> Unit>()
        synchronized(this) {
            val identityChanged = lastTargetIdentity != targetIdentity(lanPeer)
            targetPeer = lanPeer
            lastTargetIdentity = targetIdentity(lanPeer)
            if (identityChanged && connectJob?.isActive == true) {
                detachConnectInFlightLocked(closeActions)
                cancelDialJobLocked()
            }
            ensureDialingLocked(makeBeforeBreak = true)
        }
        DetachedNetworkResources(closeActions).closeAsync(scope, TAG)
    }

    override fun sendFrame(frame: LanFrame) {
        session?.takeIf { !it.closed }?.sendFrame(frame)
    }

    override fun dropAndRetry() {
        session?.close()
    }

    override fun stop() {
        val closeActions = mutableListOf<() -> Unit>()
        synchronized(this) {
            targetPeer = null
            lastTargetIdentity = null
            detachConnectInFlightLocked(closeActions)
            cancelDialJobLocked()
            standby?.let { closeActions += { it.close() } }
            standby = null
            session?.let { closeActions += { it.close() } }
            session = null
            sessionRoute = null
            sessionDirection = null
        }
        DetachedNetworkResources(closeActions).closeAsync(scope, TAG)
    }

    internal fun adoptInboundSession(
        peerUid: String,
        socket: Socket,
        out: DataOutputStream,
        input: DataInputStream,
        peerAnnouncedLastContiguousSeq: Long,
        incomingRelayOffer: com.nblaisot.voxcrew.relay.RelayConfigLink? = null,
    ) {
        if (this.peerUid != peerUid) {
            runCatching { socket.close() }
            return
        }
        val route = inboundRouteResolver(socket)
        if (route == null) {
            Log.w(TAG, "rejected inbound peer=$peerUid: socket path is not in connectivity snapshot")
            runCatching { socket.close() }
            return
        }
        if (route.path == PeerPath.LAN) {
            incomingRelayOffer?.let { onRelayOffer?.invoke(peerUid, it) }
        }
        if (hasHealthyLocalSession() && route.path == PeerPath.OVERLAY) {
            replaceStandby(
                ParkedSession(
                    peerUid = peerUid,
                    socket = socket,
                    out = out,
                    input = input,
                    peerAnnouncedLastContiguousSeq = peerAnnouncedLastContiguousSeq,
                    route = route,
                    direction = SessionDirection.INBOUND,
                ),
            )
            return
        }
        adoptSession(
            peerUid,
            socket,
            out,
            input,
            peerAnnouncedLastContiguousSeq,
            route,
            SessionDirection.INBOUND,
        )
    }

    private fun targetIdentity(target: RoutedPeerTarget): String =
        "${target.peer.host}|${target.peer.port}|${target.route.path}|${target.route.networkHandle}"

    private fun cancelDialJobLocked() {
        activeDialGeneration = ++nextDialGeneration
        connectJob?.cancel()
        connectJob = null
    }

    private fun ensureDialingLocked(makeBeforeBreak: Boolean = false) {
        val target = targetPeer ?: return
        if (target.peer.host.isBlank() || target.peer.port <= 0) return
        val current = session?.takeIf { !it.closed }
        val needsDial = current == null ||
            makeBeforeBreak ||
            !currentSessionMatchesTargetLocked(current, target)
        if (!needsDial || connectJob?.isActive == true) return
        val generation = ++nextDialGeneration
        activeDialGeneration = generation
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                connectLoop(generation)
            } finally {
                onDialLoopFinished(generation)
            }
        }
    }

    private fun onDialLoopFinished(generation: Long) {
        synchronized(this) {
            if (activeDialGeneration != generation) return
            connectJob = null
            ensureDialingLocked()
        }
    }

    private fun connectTimeoutMs(target: RoutedPeerTarget): Int =
        if (target.route.path == PeerPath.OVERLAY) {
            OVERLAY_CONNECT_TIMEOUT_MS
        } else {
            LAN_CONNECT_TIMEOUT_MS
        }

    private fun retryDelayMs(target: RoutedPeerTarget): Long =
        if (target.route.path == PeerPath.OVERLAY) {
            OVERLAY_RETRY_DELAY_MS
        } else {
            LAN_RETRY_DELAY_MS
        }

    /** Exponential backoff is independent for each endpoint + Android network handle. */
    internal fun backoffDelayMs(target: RoutedPeerTarget): Long {
        val failures = synchronized(this) { retryStates[targetIdentity(target)]?.failures ?: 0 }
        val exponent = failures.coerceIn(0, MAX_BACKOFF_EXPONENT)
        return (retryDelayMs(target) shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun recordFailureLocked(target: RoutedPeerTarget): RetryState {
        val key = targetIdentity(target)
        val state = retryStates.getOrPut(key) { RetryState() }
        state.failures++
        state.nextEligibleAtMs = clockMs() + backoffForFailures(target, state.failures)
        return state.copy()
    }

    private fun backoffForFailures(target: RoutedPeerTarget, failures: Int): Long {
        val exponent = failures.coerceIn(0, MAX_BACKOFF_EXPONENT)
        return (retryDelayMs(target) shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun confirmSessionLocked(source: LanTcpSession) {
        if (session !== source || !source.confirm()) return
        retryStates.remove(source.retryKey)
        targetPeer
            ?.takeIf { target ->
                sessionRoute?.let { route ->
                    target.route.path == route.path &&
                        target.route.networkHandle == route.networkHandle
                } == true
            }
            ?.let { retryStates.remove(targetIdentity(it)) }
        logInfo(
            "SESSION_CONFIRMED peer=${source.peerUid} generation=${source.generation} " +
                "path=${sessionRoute?.label}",
        )
    }

    private fun isLanTarget(target: RoutedPeerTarget): Boolean = target.route.path == PeerPath.LAN

    private suspend fun connectLoop(dialGeneration: Long) {
        while (currentCoroutineContext().isActive) {
            val target = synchronized(this) {
                if (activeDialGeneration != dialGeneration) return
                targetPeer
            } ?: return
            val peer = target.peer
            val targetKey = targetIdentity(target)
            val currentMatches = synchronized(this) {
                session?.takeIf { !it.closed }
                    ?.let { currentSessionMatchesTargetLocked(it, target) } == true
            }
            if (currentMatches) return
            if (peer.host.isBlank() || peer.port <= 0) return

            val waitMs = synchronized(this) {
                (retryStates[targetKey]?.nextEligibleAtMs ?: 0L) - clockMs()
            }
            if (waitMs > 0L) delay(waitMs)
            if (!currentCoroutineContext().isActive) return

            peerLink.markConnecting(peer.uid)
            val generation = cancelGeneration
            val socket = socketFactory()
            val registered = synchronized(this) {
                if (activeDialGeneration != dialGeneration ||
                    targetPeer?.let(::targetIdentity) != targetKey
                ) {
                    false
                } else {
                    connectingSocket = socket
                    true
                }
            }
            if (!registered) {
                runCatching { socket.close() }
                return
            }
            try {
                bindTargetSocket(networkSocketBinder, target, socket)
                socket.connect(InetSocketAddress(peer.host, peer.port), connectTimeoutMs(target))
                socket.tcpNoDelay = true
                if (connectingSocket !== socket || isCancelledAttempt(generation)) {
                    runCatching { socket.close() }
                    return
                }
                connectingSocket = null
                performHandshakeAndAdopt(target, socket, generation)
                return
            } catch (e: IOException) {
                synchronized(this) {
                    if (connectingSocket === socket) connectingSocket = null
                }
                runCatching { socket.close() }
                if (isCancelledAttempt(generation) ||
                    synchronized(this) { activeDialGeneration != dialGeneration }
                ) {
                    // Intentional cancel (forceRestart / path switch) — do not failover.
                    return
                }
                val failure = synchronized(this) { recordFailureLocked(target) }
                logInfo(
                    "dial failed peer=${peer.uid} host=${peer.host}:${peer.port} " +
                        "path=${target.route.path} failures=${failure.failures}: ${e.message}",
                )
                val failedLan = isLanTarget(target)
                if (failedLan) {
                    onLanDialFailed?.invoke()
                }
                // Target may have switched to overlay inside the callback.
                if (failedLan && targetPeer?.let { !isLanTarget(it) } == true) {
                    continue
                }
            }
        }
    }

    private fun currentSessionMatchesTargetLocked(
        current: LanTcpSession,
        target: RoutedPeerTarget,
    ): Boolean {
        if (current.retryKey == targetIdentity(target)) return true
        val route = sessionRoute ?: return false
        return sessionDirection == SessionDirection.INBOUND &&
            route.path == target.route.path &&
            route.networkHandle == target.route.networkHandle
    }

    private suspend fun performHandshakeAndAdopt(
        target: RoutedPeerTarget,
        socket: Socket,
        generation: Int,
    ) {
        val peerUid = target.peer.uid
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val offer = if (target.route.path == PeerPath.LAN) relayOfferProvider() else null
        LanProtocol.writeFrame(
            out,
            LanFrame.Hello(localUid, peerLink.lastContiguousInSeq(), offer),
        )
        val reply = withTimeoutOrNull(LanTcpServer.HANDSHAKE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { LanProtocol.readFrame(input) }
        }
        if (reply !is LanFrame.Hello || reply.uid != peerUid) {
            runCatching { socket.close() }
            if (isCancelledAttempt(generation)) return
            throw IOException("handshake failed")
        }
        if (target.route.path == PeerPath.LAN) {
            reply.relayOffer?.let { onRelayOffer?.invoke(peerUid, it) }
        }
        adoptSession(
            peerUid,
            socket,
            out,
            input,
            reply.lastContiguousSeq,
            target.route,
            SessionDirection.OUTBOUND,
            targetIdentity(target),
        )
    }

    private fun adoptParkedSession(parked: ParkedSession) {
        adoptSession(
            peerUid = parked.peerUid,
            socket = parked.socket,
            out = parked.out,
            input = parked.input,
            peerAnnouncedLastContiguousSeq = parked.peerAnnouncedLastContiguousSeq,
            route = parked.route,
            direction = parked.direction,
            retryKey = retryKeyForRoute(parked.route),
            forceReplace = true,
            suffix = " (standby)",
        )
    }

    private fun replaceStandby(candidate: ParkedSession) {
        val previous = synchronized(this) {
            standby.also { standby = candidate }
        }
        previous?.let { DetachedNetworkResources(listOf({ it.close() })).closeAsync(scope, TAG) }
    }

    private fun adoptSession(
        peerUid: String,
        socket: Socket,
        out: DataOutputStream,
        input: DataInputStream,
        peerAnnouncedLastContiguousSeq: Long,
        route: RoutedSocketPath,
        direction: SessionDirection,
        retryKey: String = retryKeyForRoute(route),
        forceReplace: Boolean = false,
        suffix: String = "",
    ): Boolean {
        val remote = socket.inetAddress?.hostAddress ?: "?"
        val remotePort = socket.port
        val closeActions = mutableListOf<() -> Unit>()
        var acceptedGeneration: Long? = null
        var rejectedActive: RoutedSocketPath? = null
        synchronized(this) {
            val activeSession = session?.takeIf { !it.closed }
            val activeRoute = sessionRoute
            val activeDirection = sessionDirection
            if (!forceReplace && activeSession != null && activeRoute != null && activeDirection != null &&
                !shouldReplaceSession(
                    localUid = localUid,
                    peerUid = peerUid,
                    activePath = activeRoute.path,
                    activeDirection = activeDirection,
                    candidatePath = route.path,
                    candidateDirection = direction,
                )
            ) {
                rejectedActive = activeRoute
                closeActions += { socket.close() }
                return@synchronized
            }

            standby?.let { parked -> closeActions += { parked.close() } }
            standby = null
            val previous = session
            val previousRoute = sessionRoute
            val previousDirection = sessionDirection
            val generation = ++nextSessionGeneration
            val newSession = LanTcpSession(
                scope = scope,
                peerUid = peerUid,
                generation = generation,
                retryKey = retryKey,
                socket = socket,
                out = out,
                input = input,
                onFrame = ::onSessionFrame,
                onClosed = ::onSessionClosed,
            )
            session = newSession
            sessionRoute = route
            sessionDirection = direction
            if (!newSession.start()) {
                session = previous
                sessionRoute = previousRoute
                sessionDirection = previousDirection
                closeActions += { socket.close() }
                return@synchronized
            }
            // Serialize publication with the current-session close callback. No resource
            // close occurs under this arbiter lock.
            peerLink.onHandshakeComplete(this, peerUid, peerAnnouncedLastContiguousSeq)
            previous?.let { old -> closeActions += { old.close() } }
            acceptedGeneration = generation
        }
        DetachedNetworkResources(closeActions).closeAsync(scope, TAG)
        rejectedActive?.let { active ->
            logInfo(
                "CANDIDATE_REJECTED peer=$peerUid candidate=${route.label}/${route.networkHandle} " +
                    "active=${active.label}/${active.networkHandle}",
            )
            return false
        }
        val generation = acceptedGeneration ?: return false
        logInfo(
            "CANDIDATE_ACCEPTED peer=$peerUid generation=$generation path=${route.label} " +
                "network=${route.networkHandle} remote=$remote:$remotePort$suffix",
        )
        return true
    }

    private fun retryKeyForRoute(route: RoutedSocketPath): String = synchronized(this) {
        targetPeer
            ?.takeIf { it.route.path == route.path && it.route.networkHandle == route.networkHandle }
            ?.let(::targetIdentity)
            ?: "${route.path}|${route.networkHandle}|$peerUid"
    }

    private fun onSessionFrame(source: LanTcpSession, frame: LanFrame) {
        synchronized(this) {
            if (session !== source || source.closed) return
            confirmSessionLocked(source)
            peerLink.onFrameReceived(this, frame)
        }
    }

    private fun onSessionClosed(closedSession: LanTcpSession) {
        var failedTarget: RoutedPeerTarget? = null
        val wasCurrent = synchronized(this) {
            if (session !== closedSession) return@synchronized false
            val route = sessionRoute
            session = null
            sessionRoute = null
            sessionDirection = null
            val target = targetPeer?.takeIf {
                it.peer.uid == closedSession.peerUid && targetIdentity(it) == closedSession.retryKey
            }
            if (!closedSession.confirmed && target != null) {
                failedTarget = target
                recordFailureLocked(target)
            }
            peerLink.onDisconnected(this, closedSession.peerUid)
            ensureDialingLocked()
            logInfo(
                "ACTIVE_SESSION_CLOSED peer=${closedSession.peerUid} " +
                    "generation=${closedSession.generation} path=${route?.label} " +
                    "confirmed=${closedSession.confirmed}",
            )
            true
        }
        if (!wasCurrent) {
            logInfo(
                "STALE_SESSION_CLOSED peer=${closedSession.peerUid} " +
                    "generation=${closedSession.generation}",
            )
            return
        }
        failedTarget?.let { target ->
            if (isLanTarget(target)) {
                onLanDialFailed?.invoke()
            }
        }
    }

    private data class ParkedSession(
        val peerUid: String,
        val socket: Socket,
        val out: DataOutputStream,
        val input: DataInputStream,
        val peerAnnouncedLastContiguousSeq: Long,
        val route: RoutedSocketPath,
        val direction: SessionDirection,
    ) {
        fun isOpen(): Boolean = !socket.isClosed && socket.isConnected
        fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "LanTcpClient"
        private const val LAN_CONNECT_TIMEOUT_MS = 2_000
        /** First dial through a DERP relay on cellular regularly exceeds 1 s. */
        private const val OVERLAY_CONNECT_TIMEOUT_MS = 5_000
        private const val LAN_RETRY_DELAY_MS = 500L
        private const val OVERLAY_RETRY_DELAY_MS = 250L
        internal const val MAX_RETRY_DELAY_MS = 30_000L
        private const val MAX_BACKOFF_EXPONENT = 7
        private fun logInfo(message: String) {
            runCatching { Log.i(TAG, message) }
        }
    }
}

internal enum class SessionDirection {
    INBOUND,
    OUTBOUND,
}

private data class RetryState(
    var failures: Int = 0,
    var nextEligibleAtMs: Long = 0L,
)

internal data class NetworkInvalidationResult(
    val affectedPath: PeerPath? = null,
    val lostSessionPath: PeerPath? = null,
    val detachedResources: DetachedNetworkResources = DetachedNetworkResources(),
)

internal data class DetachedNetworkResources(
    val closeActions: List<() -> Unit> = emptyList(),
) {
    fun closeAsync(scope: CoroutineScope, tag: String) {
        if (closeActions.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            closeActions.forEach { close ->
                runCatching { close() }.onFailure { error ->
                    Log.w(tag, "async network resource close failed: ${error.message}")
                }
            }
        }
    }
}

/**
 * Both peers may dial simultaneously. UID ordering makes both endpoints retain the same
 * physical TCP connection instead of each closing the connection selected by the other.
 */
internal fun shouldReplaceSession(
    localUid: String,
    peerUid: String,
    activePath: PeerPath,
    activeDirection: SessionDirection,
    candidatePath: PeerPath,
    candidateDirection: SessionDirection,
): Boolean {
    fun priority(path: PeerPath): Int = if (path == PeerPath.LAN) 2 else 1
    val candidatePriority = priority(candidatePath)
    val activePriority = priority(activePath)
    if (candidatePriority != activePriority) return candidatePriority > activePriority
    val preferredDirection = if (localUid < peerUid) {
        SessionDirection.OUTBOUND
    } else {
        SessionDirection.INBOUND
    }
    return candidateDirection == preferredDirection && activeDirection != preferredDirection
}

internal fun bindTargetSocket(
    binder: NetworkSocketBinder,
    target: RoutedPeerTarget,
    socket: Socket,
) {
    binder.bindSocket(target.route.networkHandle, socket)
}
