package com.nblaisot.voxcrew.lanlink

import android.content.Context
import com.nblaisot.voxcrew.audio.AudioPermissionIssue
import com.nblaisot.voxcrew.audio.AudioRouteSelector
import com.nblaisot.voxcrew.audio.AudioRouteState
import com.nblaisot.voxcrew.audio.CaptureInputKind
import com.nblaisot.voxcrew.audio.IntercomAudioSession
import com.nblaisot.voxcrew.audio.OutputKind
import com.nblaisot.voxcrew.audio.PushToTalkTransmissionPolicy
import com.nblaisot.voxcrew.audio.SileroVoiceDetector
import com.nblaisot.voxcrew.audio.TransmissionPolicy
import com.nblaisot.voxcrew.audio.UiFeedbackPlayer
import com.nblaisot.voxcrew.audio.VoiceActivatedTransmissionPolicy
import com.nblaisot.voxcrew.audio.VoxGate
import com.nblaisot.voxcrew.audio.VoxSensitivity
import com.nblaisot.voxcrew.connectivity.NetworkMonitor
import com.nblaisot.voxcrew.connectivity.transport.CloudRunSignalingTransport
import com.nblaisot.voxcrew.signaling.SignalingClient
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Facade for the whole intercom audio path: discovery, one [PeerConnection] per peer
 * (each with independent Local / cloud path), capture fan-out and shared playback.
 */
class LanIntercomEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val cloudTransport: CloudRunSignalingTransport,
    private val signalingClient: SignalingClient? = null,
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
    private val intercomAudioSession: IntercomAudioSession = IntercomAudioSession(context),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val beacon = LanBeacon(context, scope)
    private val lanServer = LanTcpServer(scope)
    private val sharedUdp = SharedUdpSocket()
    private val capture = AudioCapture(scope, intercomAudioSession)
    private val playback = AudioPlayback(scope, intercomAudioSession)
    private val uiFeedback = UiFeedbackPlayer(scope)

    private val connections = ConcurrentHashMap<String, PeerConnection>()
    private val audioCollectJobs = ConcurrentHashMap<String, Job>()
    private val feedbackWatchJobs = ConcurrentHashMap<String, Job>()
    private val receivingUntilMs = ConcurrentHashMap<String, Long>()

    private val pttPolicy = PushToTalkTransmissionPolicy()
    private val voxPolicy = VoiceActivatedTransmissionPolicy()
    private var activePolicy: TransmissionPolicy = pttPolicy
    private var policyWatchJob: Job? = null

    val peers: StateFlow<List<LanPeer>> = beacon.peers

    private val _activeRecipientUids = MutableStateFlow<Set<String>>(emptySet())
    val activeRecipientUids: StateFlow<Set<String>> = _activeRecipientUids.asStateFlow()

    private val _peerMetrics = MutableStateFlow<Map<String, PeerMetrics>>(emptyMap())
    val peerMetrics: StateFlow<Map<String, PeerMetrics>> = _peerMetrics.asStateFlow()

    val isReceiving: StateFlow<Boolean> = playback.isReceiving

    private val _receivingFromUids = MutableStateFlow<Set<String>>(emptySet())
    val receivingFromUids: StateFlow<Set<String>> = _receivingFromUids.asStateFlow()

    private val _voxEnabled = MutableStateFlow(false)
    val voxEnabled: StateFlow<Boolean> = _voxEnabled.asStateFlow()

    private val _voxSensitivity = MutableStateFlow(
        VoxSensitivity.coerce(prefs.getInt(KEY_VOX_SENSITIVITY, VoxSensitivity.DEFAULT.level)),
    )
    val voxSensitivity: StateFlow<VoxSensitivity> = _voxSensitivity.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    val captureInputKind: StateFlow<CaptureInputKind> = intercomAudioSession.captureInputKind
    val outputKind: StateFlow<OutputKind> = intercomAudioSession.outputKind
    val routeReady: StateFlow<Boolean> = intercomAudioSession.routeReady
    val audioRoute = intercomAudioSession.audioRoute
    val audioPermissionIssue: StateFlow<AudioPermissionIssue?> = intercomAudioSession.permissionIssue

    private var voxJob: Job? = null
    private var udpReceiveJob: Job? = null
    private var receivingSweepJob: Job? = null
    private var metricsJob: Job? = null
    private var routeWatchJob: Job? = null
    private var releaseAudioJob: Job? = null
    private val audioInitMutex = Mutex()
    private var audioPrepared = false
    private var lastFanOutDiagMs = 0L

    private var started = false
    private var localUid: String = ""
    private var displayName: String = ""
    private var knownCrewUids: Set<String> = emptySet()

    val statusText: StateFlow<String> = combine(
        _activeRecipientUids,
        _peerMetrics,
    ) { active, metrics ->
        describeStatus(active ?: emptySet(), metrics ?: emptyMap())
    }.stateIn(scope, SharingStarted.Eagerly, "Recherche de coéquipiers…")

    fun start(uid: String, displayName: String) {
        if (started) return
        started = true
        localUid = uid
        this.displayName = displayName

        lanServer.start(uid)
        lanServer.onUnknownInboundPeer = { peerUid -> ensureKnownPeer(peerUid) }
        beacon.start(uid, displayName, lanServer.localPort)
        networkMonitor.start()
        sharedUdp.open()
        startSharedUdpReceiver()
        startReceivingSweep()

        val restoreVoxEnabled = prefs.getBoolean(KEY_VOX_ENABLED, false)
        if (restoreVoxEnabled) {
            pttPolicy.cancel()
            activePolicy = voxPolicy
            _voxEnabled.value = true
        }
        watchPolicy(activePolicy)

        scope.launch {
            beacon.peers.collect { list -> updateLanTargets(list) }
        }
        scope.launch {
            cloudTransport.incomingMessages.collect { handleCloudMessage(it) }
        }
        signalingClient?.let { client ->
            scope.launch {
                client.peerOffline.collect { uid -> connections[uid]?.onPeerPresenceLost() }
            }
        }
        scope.launch {
            networkMonitor.networkChanged.collect { onNetworkChanged() }
        }

        loadPersistedActiveRecipients()
        startMetricsWatcher()
        ensureAudioRoutingMonitor()
    }

    fun syncCrewPeers(crewUids: Set<String>) {
        val newPeers = crewUids - knownCrewUids
        knownCrewUids = crewUids
        var active = _activeRecipientUids.value ?: emptySet()
        if (active.isEmpty() && crewUids.isNotEmpty()) {
            active = crewUids
        } else if (newPeers.isNotEmpty()) {
            active = active + newPeers
        }
        if (active != _activeRecipientUids.value) {
            setActiveRecipients(active, persist = true)
        }
        crewUids.forEach { uid -> ensureConnection(uid).start() }
        val removed = connections.keys - crewUids
        removed.forEach { removeConnection(it) }
    }

    fun toggleRecipient(uid: String) {
        if (uid == localUid) return
        val current = _activeRecipientUids.value
        val updated = if (uid in current) current - uid else current + uid
        setActiveRecipients(updated, persist = true)
    }

    fun soloRecipient(uid: String) {
        if (uid == localUid) return
        setActiveRecipients(setOf(uid), persist = true)
    }

    fun setActiveRecipients(uids: Set<String>, persist: Boolean = false) {
        val filtered = uids.filter { it != localUid }.toSet()
        _activeRecipientUids.value = filtered
        if (persist) saveActiveRecipients(filtered)
        filtered.forEach { ensureConnection(it).start() }
    }

    fun onMicrophonePermissionGranted() {
        if (!started) return
        ensureAudioRoutingMonitor()
    }

    fun setVoxEnabled(enabled: Boolean) {
        _voxEnabled.value = enabled
        prefs.edit().putBoolean(KEY_VOX_ENABLED, enabled).apply()
        if (enabled) {
            pttPolicy.cancel()
            activePolicy = voxPolicy
            watchPolicy(activePolicy)
            prepareAudioAndCapture()
        } else {
            stopVoxCapture()
            activePolicy = pttPolicy
            watchPolicy(activePolicy)
            releaseAudioWhenIdle(delayMs = 0L)
        }
    }

    fun setVoxSensitivity(sensitivity: VoxSensitivity) {
        _voxSensitivity.value = sensitivity
        prefs.edit().putInt(KEY_VOX_SENSITIVITY, sensitivity.level).apply()
        if (_voxEnabled.value) {
            stopVoxCapture()
            audioPrepared = false
            prepareAudioAndCapture()
        }
    }

    fun pttPress() {
        if (_voxEnabled.value) return
        prepareAudioAndCapture()
        logPttRouteSummary()
        pttPolicy.onPress()
    }

    fun pttRelease() {
        if (_voxEnabled.value) return
        pttPolicy.onRelease()
        releaseAudioWhenIdle()
    }

    fun onBluetoothPermissionGranted() {
        refreshAudioRouting()
    }

    fun refreshAudioRouting() {
        if (!started) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                audioInitMutex.withLock {
                    ensureAudioRoutingMonitorLocked()
                }
            }
        }
    }

    private fun ensureAudioRoutingMonitor() {
        if (!started) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                audioInitMutex.withLock {
                    ensureAudioRoutingMonitorLocked()
                }
            }
        }
    }

    private fun ensureAudioRoutingMonitorLocked() {
        if (!intercomAudioSession.isActive) {
            intercomAudioSession.enter()
        } else {
            intercomAudioSession.reapplyRouting()
        }
        watchAudioRoute()
    }

    private fun fanOut(payload: ByteArray) {
        val active = _activeRecipientUids.value ?: emptySet()
        if (active.isEmpty()) {
            Log.w(TAG, "fanOut: no active recipients — audio frame dropped")
            return
        }
        var missingConnection = 0
        var notConnected = 0
        active.forEach { uid ->
            val conn = connections[uid]
            if (conn == null) {
                missingConnection++
                ensureConnection(uid).start()
            } else {
                if (conn.linkState.value !is PeerLink.LinkState.Connected) notConnected++
                conn.send(payload)
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastFanOutDiagMs >= 2_000L) {
            lastFanOutDiagMs = now
            Log.i(
                TAG,
                "fanOut: recipients=${active.size} bytes=${payload.size} " +
                    "missingConnection=$missingConnection notConnected=$notConnected",
            )
        }
    }

    private fun logPttRouteSummary() {
        val route = intercomAudioSession.currentRoute()
        val active = _activeRecipientUids.value ?: emptySet()
        val connected = active.count { uid ->
            connections[uid]?.linkState?.value is PeerLink.LinkState.Connected
        }
        Log.i(
            TAG,
            "ptt route mic=${route.micKind} output=${route.outputKind} ready=${route.routeReady} " +
                "mode=${route.audioMode} usage=${route.playbackUsage} captureType=${route.captureDevice?.type} " +
                "outputType=${route.outputDevice?.type} recipients=${active.size} connected=$connected",
        )
    }

    private fun prepareAudioAndCapture() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                audioInitMutex.withLock {
                    releaseAudioJob?.cancel()
                    ensureAudioRoutingMonitorLocked()
                    intercomAudioSession.activateAudio()
                    if (!intercomAudioSession.awaitRouteReady()) {
                        Log.w(TAG, "audio route not ready; capture/playback deferred")
                        return@withLock
                    }
                    if (!intercomAudioSession.isAudioFocusGranted()) {
                        Log.w(TAG, "audio focus not granted — capture may be unreliable")
                    }
                    if (audioPrepared) return@withLock
                    playback.warmUp()
                    attachCaptureForCurrentMode()
                    audioPrepared = true
                }
            }.onFailure { error ->
                Log.e(TAG, "audio session init failed: ${error.message}", error)
            }
        }
    }

    private fun releaseAudioWhenIdle(delayMs: Long = AUDIO_IDLE_RELEASE_DELAY_MS) {
        releaseAudioJob?.cancel()
        releaseAudioJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            runCatching {
                audioInitMutex.withLock {
                    if (_voxEnabled.value || pttPolicy.shouldTransmit.value || playback.isReceiving.value) {
                        return@withLock
                    }
                    capture.detach()
                    playback.stop()
                    audioPrepared = false
                    intercomAudioSession.deactivateAudio()
                }
            }.onFailure { error ->
                Log.e(TAG, "audio idle release failed: ${error.message}", error)
            }
        }
    }

    private fun watchAudioRoute() {
        if (routeWatchJob?.isActive == true) return
        routeWatchJob = scope.launch {
            var previousPlaybackKey: String? = null
            var previousCaptureKey: String? = null
            var detachedForUnavailableRoute = false
            combine(
                intercomAudioSession.routeReady,
                intercomAudioSession.audioRoute,
            ) { ready, route ->
                ready to route
            }.collect { (ready, route) ->
                if (!audioPrepared) return@collect
                if (!ready) {
                    Log.i(TAG, "audio route became unavailable; stopping capture")
                    stopVoxCapture()
                    capture.detach()
                    previousPlaybackKey = null
                    previousCaptureKey = null
                    detachedForUnavailableRoute = true
                    return@collect
                }
                val playbackKey = playbackKey(route)
                val captureKey = captureKey(route)
                val playbackChanged = detachedForUnavailableRoute ||
                    (previousPlaybackKey != null && previousPlaybackKey != playbackKey)
                val captureChanged = detachedForUnavailableRoute ||
                    (previousCaptureKey != null && previousCaptureKey != captureKey)
                detachedForUnavailableRoute = false
                previousPlaybackKey = playbackKey
                previousCaptureKey = captureKey
                if (!playbackChanged && !captureChanged) return@collect
                Log.i(
                    TAG,
                    "audio route changed — playbackChanged=$playbackChanged captureChanged=$captureChanged",
                )
                restartAudioPath(route, playbackChanged, captureChanged)
            }
        }
    }

    private fun restartAudioPath(
        route: AudioRouteState,
        playbackChanged: Boolean,
        captureChanged: Boolean,
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                audioInitMutex.withLock {
                    if (!intercomAudioSession.awaitRouteReady()) return@withLock
                    if (playbackChanged) playback.refreshRoute(route)
                    if (captureChanged && _voxEnabled.value) {
                        stopVoxCapture()
                        startVoxCapture()
                    } else if (captureChanged) {
                        capture.detach()
                        capture.attach(activePolicy.shouldTransmit) { payload -> fanOut(payload) }
                    }
                }
            }.onFailure { error ->
                Log.e(TAG, "audio path restart failed: ${error.message}", error)
            }
        }
    }

    private fun playbackKey(route: AudioRouteState): String =
        "${route.playbackUsage}:${route.audioMode}:${AudioRouteSelector.deviceIdentity(route.outputDevice)}"

    private fun captureKey(route: AudioRouteState): String =
        "${route.micKind}:${AudioRouteSelector.deviceIdentity(route.captureDevice)}:${route.captureAudioSource}"

    private fun attachCaptureForCurrentMode() {
        if (_voxEnabled.value) {
            startVoxCapture()
        } else {
            capture.attach(activePolicy.shouldTransmit) { payload -> fanOut(payload) }
        }
    }

    private fun ensureConnection(peerUid: String): PeerConnection {
        return connections.getOrPut(peerUid) {
            val conn = PeerConnection(
                peerUid = peerUid,
                scope = scope,
                localUid = localUid,
                lanServer = lanServer,
                sharedUdp = sharedUdp,
                cloudTransport = cloudTransport,
                isStillWanted = { peerUid in knownCrewUids || peerUid in _activeRecipientUids.value },
                localIpv4Provider = ::localIpv4Address,
            )
            startAudioCollection(conn)
            startConnectionFeedback(conn)
            conn
        }
    }

    private fun ensureKnownPeer(peerUid: String) {
        if (peerUid == localUid) return
        knownCrewUids = knownCrewUids + peerUid
        val conn = ensureConnection(peerUid)
        conn.start()
        if (peerUid !in _activeRecipientUids.value) {
            setActiveRecipients(_activeRecipientUids.value + peerUid, persist = true)
        }
        val peer = beacon.peers.value.firstOrNull { it.uid == peerUid }
            ?: LanPeer(peerUid, peerUid, "", 0, System.currentTimeMillis())
        conn.updateLanTarget(peer)
    }

    private fun removeConnection(peerUid: String) {
        audioCollectJobs.remove(peerUid)?.cancel()
        feedbackWatchJobs.remove(peerUid)?.cancel()
        connections.remove(peerUid)?.stop()
        receivingUntilMs.remove(peerUid)
        refreshReceivingUids()
    }

    private fun startConnectionFeedback(conn: PeerConnection) {
        if (feedbackWatchJobs.containsKey(conn.peerUid)) return
        feedbackWatchJobs[conn.peerUid] = scope.launch(Dispatchers.Default) {
            var previous = conn.linkState.value
            conn.linkState.collect { state ->
                if (state == previous) return@collect
                val activeRecipients = _activeRecipientUids.value
                if (conn.peerUid in activeRecipients) {
                    when {
                        previous !is PeerLink.LinkState.Connected && state is PeerLink.LinkState.Connected ->
                            uiFeedback.playConnected()
                        previous is PeerLink.LinkState.Connected && state is PeerLink.LinkState.Disconnected ->
                            uiFeedback.playDisconnected()
                    }
                }
                previous = state
            }
        }
    }

    private fun startAudioCollection(conn: PeerConnection) {
        if (audioCollectJobs.containsKey(conn.peerUid)) return
        audioCollectJobs[conn.peerUid] = scope.launch(Dispatchers.IO) {
            conn.peerLink.incomingAudio.collect { payload ->
                ensureAudioRoutingMonitor()
                playback.play(payload)
                receivingUntilMs[conn.peerUid] = System.currentTimeMillis() + RECEIVING_IDLE_MS
                refreshReceivingUids()
            }
        }
    }

    private fun startReceivingSweep() {
        receivingSweepJob?.cancel()
        receivingSweepJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(200)
                refreshReceivingUids()
            }
        }
    }

    private fun refreshReceivingUids() {
        val now = System.currentTimeMillis()
        val active = receivingUntilMs.filterValues { it > now }.keys
        _receivingFromUids.value = active
    }

    private fun updateLanTargets(peerList: List<LanPeer>) {
        val visibleUids = peerList.map { it.uid }.toSet()
        connections.keys.forEach { uid ->
            if (uid != localUid && uid !in visibleUids) {
                connections[uid]?.onLanPeerAbsent()
            }
        }
        peerList.forEach { peer ->
            if (peer.uid == localUid) return@forEach
            val conn = ensureConnection(peer.uid)
            if (!conn.linkState.value.let { it is PeerLink.LinkState.Connected }) {
                conn.start()
            }
            conn.updateLanTarget(peer)
        }
    }

    private fun startSharedUdpReceiver() {
        udpReceiveJob?.cancel()
        udpReceiveJob = scope.launch(Dispatchers.IO) {
            val socket = sharedUdp.open()
            val buffer = ByteArray(2048)
            try {
                while (currentCoroutineContext().isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val from = InetSocketAddress(packet.address, packet.port)
                    val data = packet.data.copyOf(packet.length)
                    routeUdpDatagram(data, from)
                }
            } catch (e: IOException) {
                // socket closed on shutdown
            }
        }
    }

    private fun routeUdpDatagram(data: ByteArray, from: InetSocketAddress) {
        connections.values.firstOrNull { it.tryHandleUdpDatagram(data, from) }?.let { return }
        val frame = LanProtocol.decodeFrame(data) ?: return
        if (frame is LanFrame.Hello) {
            connections[frame.uid]?.tryHandleUdpDatagram(data, from)
        }
    }

    private fun handleCloudMessage(envelope: SignalingEnvelope) {
        when (envelope.type) {
            SignalingMessageTypes.RELAY_UNAVAILABLE -> {
                val recipientId = envelope.payload["recipientId"]?.jsonPrimitive?.content ?: return
                connections[recipientId]?.onPeerPresenceLost()
            }
            SignalingMessageTypes.P2P_CONNECT_REQUEST,
            SignalingMessageTypes.P2P_ENDPOINTS,
            -> {
                val sender = envelope.senderId ?: return
                ensureConnection(sender).apply {
                    start()
                    handleCloudMessage(envelope)
                }
            }
        }
    }

    private fun onNetworkChanged() {
        if (started) beacon.start(localUid, displayName, lanServer.localPort)
        connections.values.forEach { it.onNetworkChanged() }
    }

    private fun startVoxCapture() {
        val sensitivity = _voxSensitivity.value
        voxJob = capture.attachVox(
            voiceDetectorFactory = { SileroVoiceDetector(appContext, sensitivity) },
            gate = VoxGate(),
            onTransmittingChanged = { transmitting -> voxPolicy.setSpeechDetected(transmitting) },
            onFrame = { payload -> fanOut(payload) },
            isReceiving = { playback.isReceiving.value },
        )
    }

    private fun stopVoxCapture() {
        voxJob?.cancel()
        voxJob = null
        voxPolicy.setSpeechDetected(false)
        capture.detach()
    }

    private fun watchPolicy(policy: TransmissionPolicy) {
        policyWatchJob?.cancel()
        policyWatchJob = scope.launch {
            policy.shouldTransmit.collect { _isTransmitting.value = it }
        }
    }

    private fun loadPersistedActiveRecipients() {
        val persisted = runCatching {
            val raw = prefs.getString(KEY_ACTIVE_RECIPIENTS, null) ?: return
            json.decodeFromString<Set<String>?>(raw)
        }.getOrNull() ?: return
        val filtered = persisted.filter { it != localUid }.toSet()
        if (filtered.isEmpty()) return
        setActiveRecipients(filtered, persist = false)
    }

    private fun saveActiveRecipients(uids: Set<String>) {
        prefs.edit().putString(KEY_ACTIVE_RECIPIENTS, json.encodeToString(uids)).apply()
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

    private fun startMetricsWatcher() {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                _peerMetrics.value = connections.mapValues { (_, conn) ->
                    val state = conn.linkState.value
                    PeerMetrics(
                        rttMs = conn.rttMs.value,
                        pathLabel = (state as? PeerLink.LinkState.Connected)?.via,
                        backlogMs = conn.backlogMs.value,
                        linkState = state,
                    )
                }
                delay(500)
            }
        }
    }

    private fun describeStatus(active: Set<String>?, metrics: Map<String, PeerMetrics>?): String {
        val recipients = active ?: emptySet()
        val peerMetrics = metrics ?: emptyMap()
        if (recipients.isEmpty()) {
            return if (knownCrewUids.isEmpty()) "Recherche de coéquipiers…" else "Aucun destinataire actif"
        }
        val connected = recipients.count { uid ->
            peerMetrics[uid]?.linkState is PeerLink.LinkState.Connected
        }
        val pathCounts = recipients.mapNotNull { peerMetrics[it]?.pathLabel }.groupingBy { it }.eachCount()
        val pathSummary = pathCounts.entries.joinToString(" · ") { (path, count) -> "$count $path" }
        return buildString {
            append("${recipients.size} actif")
            if (recipients.size > 1) append("s")
            append(" · $connected connecté")
            if (connected > 1) append("s")
            if (pathSummary.isNotBlank()) {
                append(" · ")
                append(pathSummary)
            }
        }
    }

    companion object {
        private const val TAG = "LanIntercomEngine"
        private const val RECEIVING_IDLE_MS = 500L
        private const val AUDIO_IDLE_RELEASE_DELAY_MS = 900L
        private const val PREFS_NAME = "voxcrew_lanlink"
        private const val KEY_ACTIVE_RECIPIENTS = "active_recipient_uids"
        private const val KEY_VOX_ENABLED = "vox_enabled"
        private const val KEY_VOX_SENSITIVITY = "vox_sensitivity"
    }
}
