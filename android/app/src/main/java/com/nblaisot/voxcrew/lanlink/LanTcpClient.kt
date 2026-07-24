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
    @Volatile private var dialFailures = 0
    @Volatile private var lastTargetIdentity: String? = null
    /** Bumped on intentional cancel so in-flight dials do not count as failures. */
    @Volatile private var cancelGeneration = 0

    /** Fired after a *real* failed dial to a non-overlay (LAN) target (not intentional cancel). */
    @Volatile var onLanDialFailed: (() -> Unit)? = null

    /** Fired when an overlay endpoint looks dead (ECONNREFUSED or enough consecutive failures). */
    @Volatile var onOverlayEndpointDead: ((peerUid: String) -> Unit)? = null

    fun lastContiguousInSeq(): Long = peerLink.lastContiguousInSeq()

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
    @Synchronized
    fun setTarget(
        target: RoutedPeerTarget?,
        forceRestart: Boolean = false,
        preserveSession: Boolean = false,
    ) {
        val previous = targetPeer
        targetPeer = target
        if (target == null) {
            cancelConnectInFlight()
            connectJob?.cancel()
            connectJob = null
            clearStandby()
            session?.close()
            session = null
            sessionRoute = null
            sessionDirection = null
            lastTargetIdentity = null
            return
        }
        val identity = targetIdentity(target)
        if (identity != lastTargetIdentity) {
            // New host/port/path — start fresh. Same endpoint with a newer lastSeenMs
            // must NOT clear dialFailures (that caused the ~1 Hz LAN dial storm).
            lastTargetIdentity = identity
            dialFailures = 0
        }
        val hostChanged = previous != null && (
            previous.peer.host != target.peer.host ||
                previous.peer.port != target.peer.port ||
                previous.route != target.route
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
        if (preserveSession && hostChanged && session?.closed == false) {
            // The peer moved (e.g. new Tailscale IP): dial the new host make-before-break
            // instead of waiting for the stale session's activity timeout.
            if (connectJob?.isActive != true) {
                connectJob = scope.launch(Dispatchers.IO) { dialMakeBeforeBreak(target) }
            }
            return
        }
        if (session?.closed != false &&
            (connectJob == null || connectJob?.isActive == false)
        ) {
            connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
        }
    }

    /** Immediate retry credit after an explicit user action (PTT press, recipient toggle). */
    fun resetDialBackoff() {
        dialFailures = 0
    }

    internal fun recordDialFailureForTest() {
        dialFailures++
    }

    internal fun cancelGenerationForTest(): Int = cancelGeneration

    internal fun bumpCancelGenerationForTest() {
        cancelGeneration++
    }

    internal fun isCancelledAttempt(generationAtStart: Int): Boolean =
        generationAtStart != cancelGeneration

    /** Close only sessions and targets routed through a network that actually disappeared. */
    @Synchronized
    fun onNetworksInvalidated(networkHandles: Set<Long>): PeerPath? {
        if (networkHandles.isEmpty()) return null
        standby?.takeIf { it.route.networkHandle in networkHandles }?.let {
            it.close()
            standby = null
        }
        if (targetPeer?.route?.networkHandle in networkHandles) {
            cancelConnectInFlight()
            connectJob?.cancel()
            connectJob = null
            targetPeer = null
            lastTargetIdentity = null
        }
        val lostPath = sessionRoute?.takeIf { it.networkHandle in networkHandles }?.path
        if (lostPath != null) {
            cancelConnectInFlight()
            connectJob?.cancel()
            connectJob = null
            session?.close()
            session = null
            sessionRoute = null
            sessionDirection = null
        }
        return lostPath
    }

    @Synchronized
    fun clearStandby() {
        standby?.close()
        standby = null
    }

    /**
     * Promote a parked standby session into the active PeerLink path, if still open.
     * Caller should [setTarget] overlay with preserveSession first so [label] intent matches.
     */
    @Synchronized
    fun promoteStandby(): Boolean {
        val parked = standby
        if (parked?.isOpen() != true) {
            parked?.close()
            standby = null
            return false
        }
        standby = null
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
    fun switchToLanMakeBeforeBreak(lanPeer: RoutedPeerTarget) {
        if (lanPeer.route.path != PeerPath.LAN) return
        targetPeer = lanPeer
        lastTargetIdentity = targetIdentity(lanPeer)
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
        targetPeer = null
        lastTargetIdentity = null
        cancelConnectInFlight()
        connectJob?.cancel()
        connectJob = null
        clearStandby()
        session?.close()
        session = null
        sessionRoute = null
        sessionDirection = null
    }

    @Synchronized
    internal fun adoptInboundSession(
        peerUid: String,
        socket: Socket,
        out: DataOutputStream,
        input: DataInputStream,
        peerAnnouncedLastContiguousSeq: Long,
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

    private fun cancelConnectInFlight() {
        cancelGeneration++
        runCatching { connectingSocket?.close() }
        connectingSocket = null
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

    /**
     * Exponential backoff capped at [MAX_RETRY_DELAY_MS]. Endpoint identity change or user
     * action resets [dialFailures]; beacon heartbeats alone do not.
     */
    internal fun backoffDelayMs(target: RoutedPeerTarget): Long {
        val exponent = dialFailures.coerceIn(0, MAX_BACKOFF_EXPONENT)
        return (retryDelayMs(target) shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun isLanTarget(target: RoutedPeerTarget): Boolean = target.route.path == PeerPath.LAN

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            val target = targetPeer ?: return
            val peer = target.peer
            if (session?.closed == false) {
                return
            }
            if (peer.host.isBlank() || peer.port <= 0) {
                delay(1_000)
                continue
            }
            peerLink.markConnecting(peer.uid)
            val generation = cancelGeneration
            val socket = Socket()
            connectingSocket = socket
            try {
                bindTargetSocket(networkSocketBinder, target, socket)
                socket.connect(InetSocketAddress(peer.host, peer.port), connectTimeoutMs(target))
                socket.tcpNoDelay = true
                if (connectingSocket !== socket || isCancelledAttempt(generation)) {
                    runCatching { socket.close() }
                    continue
                }
                connectingSocket = null
                performHandshakeAndAdopt(target, socket, generation)
                if (session?.closed == false) return
            } catch (e: IOException) {
                connectingSocket = null
                runCatching { socket.close() }
                if (isCancelledAttempt(generation)) {
                    // Intentional cancel (forceRestart / path switch) — do not failover.
                    continue
                }
                dialFailures++
                Log.i(
                    TAG,
                    "dial failed peer=${peer.uid} host=${peer.host}:${peer.port} " +
                        "path=${target.route.path} failures=$dialFailures: ${e.message}",
                )
                val failedLan = isLanTarget(target)
                if (failedLan) {
                    onLanDialFailed?.invoke()
                } else {
                    maybeInvalidateOverlayEndpoint(target, e)
                }
                // Target may have switched to overlay inside the callback.
                if (failedLan && targetPeer?.let { !isLanTarget(it) } == true) {
                    continue
                }
                delay(backoffDelayMs(target))
            }
        }
    }

    private fun maybeInvalidateOverlayEndpoint(target: RoutedPeerTarget, error: IOException) {
        val msg = error.message.orEmpty()
        val refused = msg.contains("ECONNREFUSED", ignoreCase = true)
        if (refused || dialFailures >= OVERLAY_INVALIDATE_AFTER_FAILURES) {
            onOverlayEndpointDead?.invoke(target.peer.uid)
        }
    }

    private suspend fun dialMakeBeforeBreak(target: RoutedPeerTarget) {
        val lanPeer = target.peer
        if (lanPeer.host.isBlank() || lanPeer.port <= 0) return
        val socket = Socket()
        connectingSocket = socket
        try {
            bindTargetSocket(networkSocketBinder, target, socket)
            socket.connect(InetSocketAddress(lanPeer.host, lanPeer.port), connectTimeoutMs(target))
            socket.tcpNoDelay = true
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
            adoptSession(
                lanPeer.uid,
                socket,
                out,
                input,
                reply.lastContiguousSeq,
                target.route,
                SessionDirection.OUTBOUND,
            )
        } catch (_: IOException) {
            connectingSocket = null
            runCatching { socket.close() }
            if (targetPeer?.peer?.uid == lanPeer.uid) {
                if (session?.closed != false) {
                    connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
                }
            }
        }
    }

    private suspend fun performHandshakeAndAdopt(
        target: RoutedPeerTarget,
        socket: Socket,
        generation: Int,
    ) {
        val peerUid = target.peer.uid
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        LanProtocol.writeFrame(out, LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()))
        val reply = withTimeoutOrNull(LanTcpServer.HANDSHAKE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { LanProtocol.readFrame(input) }
        }
        if (reply !is LanFrame.Hello || reply.uid != peerUid) {
            runCatching { socket.close() }
            if (isCancelledAttempt(generation)) return
            // Treat a bad/missing Hello like a connect failure so overlay failover can run.
            dialFailures++
            val activeTarget = targetPeer
            if (activeTarget != null && isLanTarget(activeTarget)) {
                Log.i(
                    TAG,
                    "handshake failed peer=$peerUid " +
                        "host=${activeTarget.peer.host}:${activeTarget.peer.port}",
                )
                onLanDialFailed?.invoke()
            } else if (activeTarget != null) {
                maybeInvalidateOverlayEndpoint(activeTarget, IOException("handshake failed"))
            }
            if (activeTarget != null) {
                delay(backoffDelayMs(activeTarget))
            }
            return
        }
        adoptSession(
            peerUid,
            socket,
            out,
            input,
            reply.lastContiguousSeq,
            target.route,
            SessionDirection.OUTBOUND,
        )
    }

    @Synchronized
    private fun adoptParkedSession(parked: ParkedSession) {
        dialFailures = 0
        val previous = session
        sessionRoute = parked.route
        sessionDirection = parked.direction
        val remote = parked.socket.inetAddress?.hostAddress ?: "?"
        val remotePort = parked.socket.port
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
        previous?.close()
        newSession.start()
        peerLink.onHandshakeComplete(this, parked.peerUid, parked.peerAnnouncedLastContiguousSeq)
        Log.i(
            TAG,
            "adopted peer=${parked.peerUid} path=${parked.route.label} " +
                "network=${parked.route.networkHandle} remote=$remote:$remotePort (standby)",
        )
    }

    @Synchronized
    private fun replaceStandby(candidate: ParkedSession) {
        standby?.close()
        standby = candidate
    }

    @Synchronized
    private fun adoptSession(
        peerUid: String,
        socket: Socket,
        out: DataOutputStream,
        input: DataInputStream,
        peerAnnouncedLastContiguousSeq: Long,
        route: RoutedSocketPath,
        direction: SessionDirection,
    ) {
        val activeSession = session?.takeIf { !it.closed }
        val activeRoute = sessionRoute
        val activeDirection = sessionDirection
        if (activeSession != null && activeRoute != null && activeDirection != null &&
            !shouldReplaceSession(
                localUid = localUid,
                peerUid = peerUid,
                activePath = activeRoute.path,
                activeDirection = activeDirection,
                candidatePath = route.path,
                candidateDirection = direction,
            )
        ) {
            Log.i(
                TAG,
                "rejected duplicate peer=$peerUid candidate=${route.label}/${route.networkHandle} " +
                    "active=${activeRoute.label}/${activeRoute.networkHandle}",
            )
            runCatching { socket.close() }
            return
        }
        clearStandby()
        dialFailures = 0
        val previous = session
        sessionRoute = route
        sessionDirection = direction
        val remote = socket.inetAddress?.hostAddress ?: "?"
        val remotePort = socket.port
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
        previous?.close()
        newSession.start()
        peerLink.onHandshakeComplete(this, peerUid, peerAnnouncedLastContiguousSeq)
        Log.i(
            TAG,
            "adopted peer=$peerUid path=${route.label} network=${route.networkHandle} " +
                "remote=$remote:$remotePort",
        )
    }

    @Synchronized
    private fun onSessionClosed(closedSession: LanTcpSession) {
        if (session !== closedSession) return
        session = null
        sessionRoute = null
        sessionDirection = null
        peerLink.onDisconnected(this, closedSession.peerUid)
        val target = targetPeer
        if (target != null && target.peer.uid == closedSession.peerUid) {
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
        /** Drop sticky overlay host:port after this many consecutive real dial failures. */
        internal const val OVERLAY_INVALIDATE_AFTER_FAILURES = 5
    }
}

internal enum class SessionDirection {
    INBOUND,
    OUTBOUND,
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
