package com.nblaisot.voxcrew.lanlink

import android.content.Context
import com.nblaisot.voxcrew.audio.PushToTalkTransmissionPolicy
import com.nblaisot.voxcrew.audio.TransmissionPolicy
import com.nblaisot.voxcrew.audio.VoiceActivatedTransmissionPolicy
import com.nblaisot.voxcrew.connectivity.NetworkMonitor
import com.nblaisot.voxcrew.connectivity.transport.CloudRunSignalingTransport
import com.nblaisot.voxcrew.signaling.ConnectionState
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID

/**
 * Single facade for the whole intercom audio path: discovery, [PeerLink] (the
 * transport-agnostic protocol core), capture and playback. Deliberately owned by
 * [com.nblaisot.voxcrew.di.AppContainer] (an application-scoped singleton, independent
 * from any Activity/ViewModel) so that receiving audio and VOX transmission keep
 * working when the app is backgrounded or the screen is off — only the foreground
 * service and its notification observe it.
 *
 * The LAN is always tried first via [beacon] + [tcpTransport]. When it stalls, is
 * lost, or degrades badly, [evaluatePath] escalates the very same [peerLink] to a
 * cloud-assisted path — a UDP hole punch rendezvoused through the backend
 * ([SignalingMessageTypes.P2P_CONNECT_REQUEST]/[SignalingMessageTypes.P2P_ENDPOINTS]),
 * falling back further to a WebSocket relay ([relayTransport]) if punching fails
 * outright. [beacon] keeps broadcasting throughout, so the moment it hears the peer
 * again [tcpTransport] redials in the background and wins the switch back to Local
 * make-before-break, exactly like [PeerLink.onHandshakeComplete] already does for a
 * plain TCP reconnect — one sequence space survives every path switch.
 */
class LanIntercomEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val cloudTransport: CloudRunSignalingTransport,
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val beacon = LanBeacon(context, scope)
    private val peerLink = PeerLink(scope)
    private val tcpTransport = LanTcpTransport(scope, peerLink)
    private val udpTransport = UdpP2pTransport(scope, peerLink)
    private val relayTransport = RelayTransport(scope, peerLink, cloudTransport)
    private val capture = AudioCapture(scope)
    private val playback = AudioPlayback(scope)

    private val pttPolicy = PushToTalkTransmissionPolicy()
    private val voxPolicy = VoiceActivatedTransmissionPolicy()
    private var activePolicy: TransmissionPolicy = pttPolicy
    private var policyWatchJob: Job? = null

    val peers: StateFlow<List<LanPeer>> = beacon.peers
    val linkState: StateFlow<PeerLink.LinkState> = peerLink.state
    val isReceiving: StateFlow<Boolean> = playback.isReceiving
    val rttMs: StateFlow<Long?> = peerLink.rttMs
    val backlogMs: StateFlow<Long> = peerLink.backlogMs

    private val _selectedPeerUid = MutableStateFlow<String?>(null)
    val selectedPeerUid: StateFlow<String?> = _selectedPeerUid.asStateFlow()

    private val _voxEnabled = MutableStateFlow(false)
    val voxEnabled: StateFlow<Boolean> = _voxEnabled.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    val statusText: StateFlow<String> = combine(peers, selectedPeerUid, linkState) { peerList, selected, link ->
        describeStatus(peerList, selected, link)
    }.stateIn(scope, SharingStarted.Eagerly, describeStatus(peers.value, selectedPeerUid.value, linkState.value))

    private var started = false
    private var localUid: String = ""
    private var displayName: String = ""

    private var pathWatchJob: Job? = null
    private var fallbackJob: Job? = null
    @Volatile private var cloudFallbackEngaged = false
    private var stateSinceMs = System.currentTimeMillis()
    private var lastObservedState: PeerLink.LinkState? = null

    fun start(uid: String, displayName: String) {
        if (started) return
        started = true
        localUid = uid
        this.displayName = displayName

        tcpTransport.startServer(uid)
        tcpTransport.onInboundPeer = { peerUid -> if (_selectedPeerUid.value == null) selectPeer(peerUid) }
        beacon.start(uid, displayName, tcpTransport.localPort)
        networkMonitor.start()

        capture.attach(activePolicy.shouldTransmit) { payload -> peerLink.send(payload) }
        watchPolicy(activePolicy)

        scope.launch(Dispatchers.IO) {
            peerLink.incomingAudio.collect { payload -> playback.play(payload) }
        }
        scope.launch {
            beacon.peers.collect { list ->
                val selected = _selectedPeerUid.value
                if (selected != null) {
                    list.firstOrNull { it.uid == selected }?.let { tcpTransport.setTarget(it) }
                } else if (list.size == 1) {
                    selectPeer(list.first().uid)
                }
            }
        }
        scope.launch {
            cloudTransport.incomingMessages.collect { handleCloudMessage(it) }
        }
        scope.launch {
            networkMonitor.networkChanged.collect { onNetworkChanged() }
        }
        startPathWatcher()

        // Restore the standing target from a previous launch: the user doesn't have to
        // re-select their teammate every time they open the app, and this lets the
        // path manager start trying to reach them (local, then cloud) right away.
        prefs.getString(KEY_SELECTED_PEER, null)?.let { persisted -> selectPeer(persisted) }
    }

    fun selectPeer(uid: String) {
        if (_selectedPeerUid.value == uid) return
        deactivateCloudFallback()
        _selectedPeerUid.value = uid
        prefs.edit().putString(KEY_SELECTED_PEER, uid).apply()
        peerLink.resetFor(uid)
        stateSinceMs = System.currentTimeMillis()
        lastObservedState = peerLink.state.value
        val peer = beacon.peers.value.firstOrNull { it.uid == uid }
            ?: LanPeer(uid = uid, displayName = uid, host = "", port = 0, lastSeenMs = System.currentTimeMillis())
        tcpTransport.setTarget(peer)
    }

    fun clearSelection() {
        deactivateCloudFallback()
        _selectedPeerUid.value = null
        prefs.edit().remove(KEY_SELECTED_PEER).apply()
        tcpTransport.setTarget(null)
        peerLink.clear()
    }

    fun setVoxEnabled(enabled: Boolean) {
        _voxEnabled.value = enabled
        activePolicy = if (enabled) {
            voxPolicy.setSpeechDetected(true)
            voxPolicy
        } else {
            voxPolicy.setSpeechDetected(false)
            pttPolicy.cancel()
            pttPolicy
        }
        watchPolicy(activePolicy)
        capture.attach(activePolicy.shouldTransmit) { payload -> peerLink.send(payload) }
    }

    fun pttPress() {
        if (_voxEnabled.value) return
        pttPolicy.onPress()
    }

    fun pttRelease() {
        if (_voxEnabled.value) return
        pttPolicy.onRelease()
    }

    private fun watchPolicy(policy: TransmissionPolicy) {
        policyWatchJob?.cancel()
        policyWatchJob = scope.launch {
            policy.shouldTransmit.collect { _isTransmitting.value = it }
        }
    }

    /**
     * Runs continuously while a peer is selected, deciding whether to escalate to the
     * cloud path (see the fallback triggers documented on the class) or to unwind it
     * once Local is healthy again.
     */
    private fun startPathWatcher() {
        pathWatchJob?.cancel()
        pathWatchJob = scope.launch(Dispatchers.Default) {
            while (currentCoroutineContext().isActive) {
                delay(WATCH_INTERVAL_MS)
                evaluatePath()
            }
        }
    }

    private fun evaluatePath() {
        val peerUid = _selectedPeerUid.value ?: return
        val state = linkState.value
        if (state != lastObservedState) {
            lastObservedState = state
            stateSinceMs = System.currentTimeMillis()
        }
        val elapsed = System.currentTimeMillis() - stateSinceMs
        val connectedVia = (state as? PeerLink.LinkState.Connected)?.via

        if (connectedVia == tcpTransport.label) {
            val rtt = rttMs.value
            val degraded = (rtt != null && rtt > PeerLink.DEGRADED_RTT_MS) ||
                peerLink.oldestUnackedAgeMs() > PeerLink.DEGRADED_UNACKED_AGE_MS
            if (degraded) triggerCloudFallback(peerUid) else deactivateCloudFallback()
            return
        }
        if (connectedVia != null) return // already on a cloud path; the beacon keeps racing to reclaim Local

        val stalled = (state is PeerLink.LinkState.Idle || state is PeerLink.LinkState.Connecting) &&
            elapsed > LOCAL_DISCOVERY_TIMEOUT_MS
        val lost = state is PeerLink.LinkState.Disconnected && elapsed > LOCAL_LINK_LOST_MS
        if (stalled || lost) triggerCloudFallback(peerUid)
    }

    /** Starts (idempotently) the cloud rendezvous: STUN + endpoint exchange, UDP punch, relay as last resort. */
    private fun triggerCloudFallback(peerUid: String) {
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
            announceEndpoints(peerUid)
            delay(UDP_CONNECT_GRACE_MS)
            if (currentCoroutineContext().isActive &&
                _selectedPeerUid.value == peerUid &&
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

    /** Discovers our public (and best-effort local) UDP endpoint and hands it to [peerUid] via the backend. */
    private suspend fun announceEndpoints(peerUid: String) {
        cloudTransport.connectCloud()
        awaitCloudAuthenticated()
        withContext(Dispatchers.IO) {
            udpTransport.openSocket()
            val endpoint = udpTransport.discoverPublicEndpoint(UdpP2pTransport.DEFAULT_STUN_HOST, UdpP2pTransport.DEFAULT_STUN_PORT)
                ?: return@withContext
            val local = localIpv4Address()
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

    private fun handleCloudMessage(envelope: SignalingEnvelope) {
        val selected = _selectedPeerUid.value ?: return
        if (envelope.senderId != selected) return
        when (envelope.type) {
            SignalingMessageTypes.P2P_CONNECT_REQUEST -> {
                scope.launch(Dispatchers.IO) { announceEndpoints(selected) }
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
                udpTransport.start(localUid, selected, candidates)
                if (fallbackJob?.isActive != true) {
                    // We never triggered our own fallback (our Local link may be fine), but the
                    // punch only works if both sides send — answer with our own endpoint too.
                    scope.launch(Dispatchers.IO) { announceEndpoints(selected) }
                }
            }
        }
    }

    /** Our own connectivity changed: re-broadcast on the (possibly new) interfaces, and if we're on
     * a cloud path our public endpoint may have changed too, so redo the rendezvous. */
    private fun onNetworkChanged() {
        if (started) beacon.start(localUid, displayName, tcpTransport.localPort)
        val selected = _selectedPeerUid.value ?: return
        val via = (linkState.value as? PeerLink.LinkState.Connected)?.via
        if (via != null && via != tcpTransport.label) {
            fallbackJob?.cancel()
            cloudFallbackEngaged = true
            fallbackJob = scope.launch(Dispatchers.IO) { announceEndpoints(selected) }
        }
    }

    private fun localIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.map { it.hostAddress }
            ?.firstOrNull()
    }.getOrNull()

    private fun describeStatus(peerList: List<LanPeer>, selected: String?, linkState: PeerLink.LinkState): String {
        if (selected == null) {
            return if (peerList.isEmpty()) "Recherche de coéquipiers…" else "Coéquipier détecté"
        }
        return when (linkState) {
            is PeerLink.LinkState.Connected -> "${linkState.via} — connecté"
            is PeerLink.LinkState.Connecting -> "Connexion…"
            is PeerLink.LinkState.Disconnected -> "Reconnexion…"
            PeerLink.LinkState.Idle -> "En attente…"
        }
    }

    companion object {
        private const val WATCH_INTERVAL_MS = 500L
        private const val LOCAL_DISCOVERY_TIMEOUT_MS = 5_000L
        private const val LOCAL_LINK_LOST_MS = 3_000L
        private const val UDP_CONNECT_GRACE_MS = 7_000L
        private const val PREFS_NAME = "voxcrew_lanlink"
        private const val KEY_SELECTED_PEER = "selected_peer_uid"
    }
}
