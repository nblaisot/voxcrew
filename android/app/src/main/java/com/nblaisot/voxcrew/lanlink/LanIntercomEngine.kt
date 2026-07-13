package com.nblaisot.voxcrew.lanlink

import android.content.Context
import com.nblaisot.voxcrew.audio.AudioPipelineState
import com.nblaisot.voxcrew.audio.CaptureInputKind
import com.nblaisot.voxcrew.audio.IntercomTelecomSession
import com.nblaisot.voxcrew.audio.ObservedAudioDeviceKind
import com.nblaisot.voxcrew.audio.OutputKind
import com.nblaisot.voxcrew.audio.isConfirmedDuplexReady
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
import kotlinx.coroutines.flow.map
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
    telecomSession: IntercomTelecomSession? = null,
) {
    private val appContext = context.applicationContext
    private val telecomSession = telecomSession ?: IntercomTelecomSession(appContext, scope)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val beacon = LanBeacon(context, scope)
    private val lanServer = LanTcpServer(scope)
    private val sharedUdp = SharedUdpSocket()
    private val capture = AudioCapture(
        scope = scope,
        telecomSession = this.telecomSession,
    )
    private val playback = AudioPlayback(scope)
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

    private val _appForeground = MutableStateFlow(false)
    val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()

    private val _voxSensitivity = MutableStateFlow(
        VoxSensitivity.coerce(prefs.getInt(KEY_VOX_SENSITIVITY, VoxSensitivity.DEFAULT.level)),
    )
    val voxSensitivity: StateFlow<VoxSensitivity> = _voxSensitivity.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    val audioRoute = this.telecomSession.callState
    val audioRouteSelection = this.telecomSession.routeSelection
    private val _audioPipelineState = MutableStateFlow<AudioPipelineState>(AudioPipelineState.Closed)
    val audioPipelineState: StateFlow<AudioPipelineState> = _audioPipelineState.asStateFlow()
    val captureInputKind: StateFlow<CaptureInputKind> = audioRoute
        .combine(audioPipelineState) { route, pipeline ->
            when ((pipeline as? AudioPipelineState.Ready)?.observedInput) {
                ObservedAudioDeviceKind.BLUETOOTH -> CaptureInputKind.BLUETOOTH
                ObservedAudioDeviceKind.USB -> CaptureInputKind.USB
                ObservedAudioDeviceKind.WIRED -> CaptureInputKind.WIRED
                ObservedAudioDeviceKind.BUILTIN -> CaptureInputKind.BUILTIN
                else -> route.micKind
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, audioRoute.value.micKind)
    val outputKind: StateFlow<OutputKind> = audioRoute
        .map { route -> route.outputKind }
        .stateIn(scope, SharingStarted.Eagerly, audioRoute.value.outputKind)
    val routeReady: StateFlow<Boolean> = audioRoute
        .combine(audioPipelineState) { call, pipeline ->
            isConfirmedDuplexReady(call, pipeline)
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var voxJob: Job? = null
    private var udpReceiveJob: Job? = null
    private var receivingSweepJob: Job? = null
    private var metricsJob: Job? = null
    private var routeWatchJob: Job? = null
    private val audioInitMutex = Mutex()
    private val mediaDemandMutex = Mutex()
    private val mediaDemandState = MediaDemandState()
    private val incomingMediaMutex = Mutex()
    private val pendingIncomingMedia = mutableMapOf<String, ArrayDeque<IncomingMediaEvent>>()
    private var audioPrepared = false
    private var lastFanOutDiagMs = 0L

    private var started = false
    private var localUid: String = ""
    private var displayName: String = ""
    private var knownCrewUids: Set<String> = emptySet()

    init {
        this.telecomSession.setMediaLifecycleCallbacks(
            onInactive = { closeAudioForTelecomLifecycle() },
            onActive = { reconcileAudioForTelecomLifecycle() },
            onDisconnected = { preserveTransmission ->
                closeAudioForTelecomLifecycle(cancelTransmission = !preserveTransmission)
            },
        )
    }

    val statusText: StateFlow<String> = combine(
        _activeRecipientUids,
        _peerMetrics,
    ) { active, metrics ->
        describeStatus(active ?: emptySet(), metrics ?: emptyMap())
    }.stateIn(scope, SharingStarted.Eagerly, "Recherche de coéquipiers…")

    fun start(uid: String, displayName: String) {
        if (started) {
            if (mediaDemandState.setSessionActive(true)) scheduleMediaDemandReconciliation()
            ensureAudioRoutingMonitor()
            return
        }
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
        mediaDemandState.setVoxEnabled(restoreVoxEnabled)
        mediaDemandState.setSessionActive(true)
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
        scheduleMediaDemandReconciliation()
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
        val added = filtered - _activeRecipientUids.value
        _activeRecipientUids.value = filtered
        if (persist) saveActiveRecipients(filtered)
        filtered.forEach { ensureConnection(it).start() }
        if (isOutboundMediaActive()) {
            added.forEach { uid -> connections[uid]?.sendMediaActivity(true) }
        }
    }

    fun onMicrophonePermissionGranted() {
        if (mediaDemandState.setMicrophonePermissionGranted(true)) {
            scheduleMediaDemandReconciliation()
        }
        if (!started) return
        ensureAudioRoutingMonitor()
    }

    fun onMicrophonePermissionDenied() {
        mediaDemandState.setMicrophonePermissionGranted(false)
        pttPolicy.cancel()
        setOutboundMediaActive(false)
        if (!started) return
        telecomSession.stop()
        scope.launch(Dispatchers.IO) {
            audioInitMutex.withLock { closeAudioPathLocked(AudioPipelineState.Closed) }
        }
    }

    fun setVoxEnabled(enabled: Boolean) {
        _voxEnabled.value = enabled
        prefs.edit().putBoolean(KEY_VOX_ENABLED, enabled).apply()
        if (enabled) {
            pttPolicy.cancel()
            stopVoxCapture()
            setOutboundMediaActive(false)
            activePolicy = voxPolicy
            watchPolicy(activePolicy)
        } else {
            stopVoxCapture()
            setOutboundMediaActive(false)
            activePolicy = pttPolicy
            watchPolicy(activePolicy)
        }
        if (mediaDemandState.setVoxEnabled(enabled)) scheduleMediaDemandReconciliation()
        if (mediaDemanded()) prepareAudioPath()
    }

    fun setAppForeground(foreground: Boolean) {
        _appForeground.value = foreground
        if (!foreground && !_voxEnabled.value) {
            pttPolicy.cancel()
            setOutboundMediaActive(false)
        }
        if (mediaDemandState.setAppForeground(foreground)) {
            scheduleMediaDemandReconciliation()
        }
    }

    fun setVoxSensitivity(sensitivity: VoxSensitivity) {
        _voxSensitivity.value = sensitivity
        prefs.edit().putInt(KEY_VOX_SENSITIVITY, sensitivity.level).apply()
        if (_voxEnabled.value) {
            stopVoxCapture()
            audioPrepared = false
            prepareAudioPath()
        }
    }

    fun pttPress() {
        if (_voxEnabled.value) return
        logPttRouteSummary()
        pttPolicy.onPress()
    }

    fun pttRelease() {
        if (_voxEnabled.value) return
        pttPolicy.onRelease()
    }

    fun onBluetoothPermissionGranted() {
        telecomSession.refreshEndpointCatalog()
        refreshAudioRouting()
    }

    fun selectAudioRoute(key: String) {
        telecomSession.selectAudioRoute(key)
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
        watchAudioRoute()
        if (mediaDemanded()) telecomSession.refresh()
    }

    private fun prepareAudioPath() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                audioInitMutex.withLock {
                    prepareAudioPathLocked(forceReattach = true)
                }
            }.onFailure { error ->
                Log.e(TAG, "audio path prepare failed: ${error.message}", error)
            }
        }
    }

    private fun prepareAudioPathLocked(forceReattach: Boolean = false) {
        val call = telecomSession.currentState
        val endpointKey = call.endpointKey
        if (!call.mediaActive || endpointKey == null) {
            closeAudioPathLocked(AudioPipelineState.Closed, cancelTransmission = false)
            return
        }
        val ready = _audioPipelineState.value as? AudioPipelineState.Ready
        if (!forceReattach && ready?.endpointKey == endpointKey) return

        _audioPipelineState.value = AudioPipelineState.Opening(endpointKey)
        stopVoxCapture()
        playback.stop()
        audioPrepared = false

        val playbackResult = playback.open(call)
        if (playbackResult is PlaybackStartResult.Failure) {
            failAudioPathLocked(playbackResult.reason)
            return
        }
        val captureResult = attachCaptureForCurrentMode()
        if (captureResult is CaptureStartResult.Failure) {
            failAudioPathLocked(captureResult.reason)
            return
        }
        playbackResult as PlaybackStartResult.Success
        captureResult as CaptureStartResult.Success
        audioPrepared = true
        _audioPipelineState.value = AudioPipelineState.Ready(
            endpointKey = endpointKey,
            observedInput = captureResult.observedInput,
            observedOutput = playbackResult.observedOutput,
        )
        Log.i(
            TAG,
            "duplex ready endpoint=${call.currentEndpoint?.name} key=$endpointKey " +
                "input=${captureResult.observedInput} output=${playbackResult.observedOutput}",
        )
        drainPendingIncomingMedia()
    }

    private fun closeAudioPathLocked(
        state: AudioPipelineState,
        cancelTransmission: Boolean = true,
    ) {
        if (cancelTransmission) pttPolicy.cancel()
        stopVoxCapture()
        playback.stop()
        audioPrepared = false
        _audioPipelineState.value = state
        if (cancelTransmission) {
            setOutboundMediaActive(false)
        }
    }

    private fun failAudioPathLocked(reason: String) {
        Log.e(TAG, "duplex pipeline failed: $reason")
        closeAudioPathLocked(AudioPipelineState.Failed(reason))
        mediaDemandState.setPipelineUsable(false)
        scheduleMediaDemandReconciliation()
    }

    private suspend fun closeAudioForTelecomLifecycle(cancelTransmission: Boolean = true) {
        audioInitMutex.withLock {
            val state = _audioPipelineState.value
            closeAudioPathLocked(
                if (state is AudioPipelineState.Failed) state else AudioPipelineState.Closed,
                cancelTransmission = cancelTransmission,
            )
        }
    }

    private suspend fun reconcileAudioForTelecomLifecycle() {
        audioInitMutex.withLock { prepareAudioPathLocked() }
    }

    private fun onAudioPipelineFailure(reason: String) {
        scope.launch(Dispatchers.IO) {
            audioInitMutex.withLock { failAudioPathLocked(reason) }
        }
    }

    fun retryAudioPipeline() {
        scope.launch(Dispatchers.IO) {
            telecomSession.retrySelectedRoute()
            mediaDemandState.setPipelineUsable(true)
            val pendingPeers = incomingMediaMutex.withLock {
                pendingIncomingMedia.filterValues { it.isNotEmpty() }.keys.toList()
            }
            pendingPeers.forEach { setRemoteTelecomDemand(it, true) }
            if (mediaDemanded()) {
                reconcileMediaDemand()
                audioInitMutex.withLock { prepareAudioPathLocked(forceReattach = true) }
            } else {
                audioInitMutex.withLock { _audioPipelineState.value = AudioPipelineState.Closed }
            }
        }
    }

    private fun setRemoteTelecomDemand(peerUid: String, active: Boolean) {
        val changed = mediaDemandState.setRemote(peerUid, active)
        if (changed) scheduleMediaDemandReconciliation()
    }

    private fun setOutboundMediaActive(active: Boolean) {
        val changed = mediaDemandState.setOutbound(active)
        if (!changed) return
        _activeRecipientUids.value.forEach { uid ->
            ensureConnection(uid).apply {
                start()
                sendMediaActivity(active)
            }
        }
        Log.i(TAG, "outbound media activity=$active recipients=${_activeRecipientUids.value.size}")
    }

    private fun isOutboundMediaActive(): Boolean =
        mediaDemandState.isOutbound()

    private fun mediaDemanded(): Boolean = mediaDemandState.isDemanded()

    private fun scheduleMediaDemandReconciliation() {
        scope.launch(Dispatchers.IO) { reconcileMediaDemand() }
    }

    private suspend fun reconcileMediaDemand() {
        mediaDemandMutex.withLock {
            while (true) {
                val demanded = mediaDemanded()
                when (telecomDemandAction(demanded, telecomSession.isActive, telecomSession.hasCall)) {
                    TelecomDemandAction.NONE -> return
                    TelecomDemandAction.ACTIVATE -> {
                        if (!telecomSession.activate()) {
                            telecomSession.stop()
                            audioInitMutex.withLock {
                                failAudioPathLocked("Telecom could not activate media")
                            }
                            return
                        }
                    }
                    TelecomDemandAction.DISCONNECT -> telecomSession.disconnect()
                }
            }
        }
    }

    private fun fanOut(payload: ByteArray) {
        val active = _activeRecipientUids.value ?: emptySet()
        if (active.isEmpty()) {
            Log.w(TAG, "fanOut: no active recipients — audio frame dropped")
            return
        }
        var deliveredImmediately = 0
        var queuedForConnection = 0
        val queuedUids = mutableListOf<String>()
        active.forEach { uid ->
            val conn = ensureConnection(uid)
            conn.start()
            val connected = conn.linkState.value is PeerLink.LinkState.Connected
            conn.send(payload)
            if (connected) {
                deliveredImmediately++
            } else {
                queuedForConnection++
                queuedUids.add(uid)
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastFanOutDiagMs >= 2_000L) {
            lastFanOutDiagMs = now
            Log.i(
                TAG,
                "fanOut: recipients=${active.size} deliveredImmediately=$deliveredImmediately " +
                    "queuedForConnection=$queuedForConnection bytes=${payload.size} queuedUids=$queuedUids",
            )
        }
    }

    private fun logPttRouteSummary() {
        val route = telecomSession.currentState
        val active = _activeRecipientUids.value ?: emptySet()
        val connected = active.count { uid ->
            connections[uid]?.linkState?.value is PeerLink.LinkState.Connected
        }
        Log.i(
            TAG,
            "ptt endpoint=${route.currentEndpoint?.name} endpointType=${route.currentEndpoint?.type} " +
                "pipeline=${_audioPipelineState.value} recipients=${active.size} connected=$connected",
        )
    }

    private fun watchAudioRoute() {
        if (routeWatchJob?.isActive == true) return
        routeWatchJob = scope.launch(Dispatchers.IO) {
            audioRoute.collect { route ->
                runCatching {
                    audioInitMutex.withLock {
                        if (!route.mediaActive) {
                            if (_audioPipelineState.value !is AudioPipelineState.Closed &&
                                _audioPipelineState.value !is AudioPipelineState.Failed
                            ) {
                                closeAudioPathLocked(
                                    AudioPipelineState.Closed,
                                    cancelTransmission = false,
                                )
                            }
                            return@withLock
                        }
                        prepareAudioPathLocked()
                    }
                }.onFailure { error ->
                    Log.e(TAG, "audio endpoint transition failed: ${error.message}", error)
                }
            }
        }
    }

    private fun attachCaptureForCurrentMode(): CaptureStartResult =
        if (_voxEnabled.value) {
            startVoxCapture()
        } else {
            capture.attach(
                shouldTransmit = activePolicy.shouldTransmit,
                onFrame = { payload -> fanOut(payload) },
                onTransmissionStopped = {
                    setOutboundMediaActive(false)
                },
                onFailure = ::onAudioPipelineFailure,
            )
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
        setRemoteTelecomDemand(peerUid, false)
        scope.launch(Dispatchers.IO) {
            incomingMediaMutex.withLock { pendingIncomingMedia.remove(peerUid) }
        }
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
                if (previous is PeerLink.LinkState.Connected &&
                    state is PeerLink.LinkState.Disconnected
                ) {
                    setRemoteTelecomDemand(conn.peerUid, false)
                    incomingMediaMutex.withLock { pendingIncomingMedia.remove(conn.peerUid) }
                }
                if (conn.peerUid in activeRecipients) {
                    when {
                        previous !is PeerLink.LinkState.Connected && state is PeerLink.LinkState.Connected ->
                            uiFeedback.playConnected().also {
                                if (isOutboundMediaActive()) conn.sendMediaActivity(true)
                            }
                        previous is PeerLink.LinkState.Connected && state is PeerLink.LinkState.Disconnected -> {
                            uiFeedback.playDisconnected()
                        }
                    }
                }
                previous = state
            }
        }
    }

    private fun startAudioCollection(conn: PeerConnection) {
        if (audioCollectJobs.containsKey(conn.peerUid)) return
        audioCollectJobs[conn.peerUid] = scope.launch(Dispatchers.IO) {
            conn.peerLink.incomingMedia.collect { event -> handleIncomingMedia(conn.peerUid, event) }
        }
    }

    private suspend fun handleIncomingMedia(peerUid: String, event: IncomingMediaEvent) {
        incomingMediaMutex.withLock {
            when (event) {
                is IncomingMediaEvent.Activity -> {
                    if (event.active) {
                        if (_audioPipelineState.value is AudioPipelineState.Failed) {
                            enqueueIncomingLocked(peerUid, event)
                        } else {
                            setRemoteTelecomDemand(peerUid, true)
                        }
                    } else if (pendingIncomingMedia[peerUid].isNullOrEmpty()) {
                        setRemoteTelecomDemand(peerUid, false)
                    } else {
                        enqueueIncomingLocked(peerUid, event)
                    }
                }
                is IncomingMediaEvent.Audio -> {
                    if (_audioPipelineState.value is AudioPipelineState.Failed) {
                        enqueueIncomingLocked(peerUid, event)
                    } else if (isConfirmedDuplexReady(
                            telecomSession.currentState,
                            _audioPipelineState.value,
                        )
                    ) {
                        setRemoteTelecomDemand(peerUid, true)
                        playIncomingLocked(peerUid, event.payload)
                    } else {
                        setRemoteTelecomDemand(peerUid, true)
                        enqueueIncomingLocked(peerUid, event)
                    }
                }
            }
        }
    }

    private fun enqueueIncomingLocked(peerUid: String, event: IncomingMediaEvent) {
        val queue = pendingIncomingMedia.getOrPut(peerUid) { ArrayDeque() }
        if (queue.size >= MAX_PENDING_INCOMING_EVENTS) {
            val audioIndex = queue.indexOfFirst { it is IncomingMediaEvent.Audio }
            if (audioIndex >= 0) queue.removeAt(audioIndex) else queue.removeFirst()
            Log.w(TAG, "incoming activation buffer full; oldest event dropped peer=$peerUid")
        }
        queue.addLast(event)
    }

    private fun drainPendingIncomingMedia() {
        scope.launch(Dispatchers.IO) {
            incomingMediaMutex.withLock {
                pendingIncomingMedia.keys.toList().forEach { peerUid ->
                    val queue = pendingIncomingMedia[peerUid] ?: return@forEach
                    while (queue.isNotEmpty() &&
                        isConfirmedDuplexReady(telecomSession.currentState, _audioPipelineState.value)
                    ) {
                        when (val event = queue.removeFirst()) {
                            is IncomingMediaEvent.Audio -> {
                                setRemoteTelecomDemand(peerUid, true)
                                if (!playIncomingLocked(peerUid, event.payload)) break
                            }
                            is IncomingMediaEvent.Activity ->
                                setRemoteTelecomDemand(peerUid, event.active)
                        }
                    }
                    if (queue.isEmpty()) pendingIncomingMedia.remove(peerUid)
                }
            }
        }
    }

    private fun playIncomingLocked(peerUid: String, payload: ByteArray): Boolean {
        if (!playback.play(payload)) {
            if (_audioPipelineState.value is AudioPipelineState.Ready) {
                onAudioPipelineFailure("AudioTrack could not play a received frame")
            }
            return false
        }
        receivingUntilMs[peerUid] = System.currentTimeMillis() + RECEIVING_IDLE_MS
        refreshReceivingUids()
        return true
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

    private fun startVoxCapture(): CaptureStartResult {
        val sensitivity = _voxSensitivity.value
        val result = capture.attachVox(
            voiceDetectorFactory = { SileroVoiceDetector(appContext, sensitivity) },
            gate = VoxGate(),
            onTransmittingChanged = { transmitting ->
                voxPolicy.setSpeechDetected(transmitting)
                setOutboundMediaActive(transmitting)
            },
            onFrame = { payload -> fanOut(payload) },
            isReceiving = { playback.isReceiving.value },
            onFailure = ::onAudioPipelineFailure,
        )
        if (result is CaptureStartResult.Success) voxJob = result.job
        return result
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
            policy.shouldTransmit.collect { transmitting ->
                _isTransmitting.value = transmitting
                if (_voxEnabled.value) return@collect
                if (transmitting) {
                    setOutboundMediaActive(true)
                } else if (_audioPipelineState.value !is AudioPipelineState.Ready) {
                    setOutboundMediaActive(false)
                }
            }
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

    fun releaseAudioSession() {
        routeWatchJob?.cancel()
        routeWatchJob = null
        pttPolicy.cancel()
        stopVoxCapture()
        setOutboundMediaActive(false)
        mediaDemandState.endSession()
        playback.stop()
        telecomSession.stop()
        audioPrepared = false
        _audioPipelineState.value = AudioPipelineState.Closed
    }

    companion object {
        private const val TAG = "LanIntercomEngine"
        private const val RECEIVING_IDLE_MS = 500L
        private const val MAX_PENDING_INCOMING_EVENTS = 250
        private const val PREFS_NAME = "voxcrew_lanlink"
        private const val KEY_ACTIVE_RECIPIENTS = "active_recipient_uids"
        private const val KEY_VOX_ENABLED = "vox_enabled"
        private const val KEY_VOX_SENSITIVITY = "vox_sensitivity"
    }
}
