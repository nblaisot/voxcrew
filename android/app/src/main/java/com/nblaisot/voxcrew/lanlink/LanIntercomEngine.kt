package com.nblaisot.voxcrew.lanlink

import android.content.Context
import com.nblaisot.voxcrew.R
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
import com.nblaisot.voxcrew.connectivity.ConnectivitySnapshot
import com.nblaisot.voxcrew.connectivity.NetworkMonitor
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Facade for the whole intercom audio path: discovery, one [PeerConnection] per peer
 * (LAN TCP with optional Tailscale overlay), capture fan-out and shared playback.
 */
class LanIntercomEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
    telecomSession: IntercomTelecomSession? = null,
    private val optInRecipients: Boolean = true,
    private val overlayFallbackEnabled: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val telecomSession = telecomSession ?: IntercomTelecomSession(appContext, scope)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val beacon = LanBeacon(scope, networkMonitor)
    private val lanServer = LanTcpServer(scope)
    private val capture = AudioCapture(
        scope = scope,
        telecomSession = this.telecomSession,
    )
    private val playback = AudioPlayback(scope)
    private val mediaInboundPlayer = MediaInboundPlayer(appContext, scope)
    private val uiFeedback = UiFeedbackPlayer(scope)

    private val connections = ConcurrentHashMap<String, PeerConnection>()
    private val audioCollectJobs = ConcurrentHashMap<String, Job>()
    private val feedbackWatchJobs = ConcurrentHashMap<String, Job>()
    private val metricsWatchJobs = ConcurrentHashMap<String, Job>()
    private val receivingUntilMs = ConcurrentHashMap<String, Long>()
    private val latencyRemotePeers = ConcurrentHashMap.newKeySet<String>()
    private val receivingExpiryLock = Any()

    private val pttPolicy = PushToTalkTransmissionPolicy()
    private val voxPolicy = VoiceActivatedTransmissionPolicy()
    private var activePolicy: TransmissionPolicy = pttPolicy
    private var policyWatchJob: Job? = null

    val peers: StateFlow<List<LanPeer>> = beacon.peers

    private val _activeRecipientUids = MutableStateFlow<Set<String>>(emptySet())
    val activeRecipientUids: StateFlow<Set<String>> = _activeRecipientUids.asStateFlow()

    private val _peerMetrics = MutableStateFlow<Map<String, PeerMetrics>>(emptyMap())
    val peerMetrics: StateFlow<Map<String, PeerMetrics>> = _peerMetrics.asStateFlow()

    val isReceiving: StateFlow<Boolean> = combine(
        playback.isReceiving,
        mediaInboundPlayer.isReceiving,
    ) { telecom, media -> telecom || media }
        .stateIn(scope, SharingStarted.Eagerly, false)

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
    private var receivingSweepJob: Job? = null
    private var routeWatchJob: Job? = null
    private val lifecycleJobs = mutableListOf<Job>()
    private val audioInitMutex = Mutex()
    private val mediaDemandMutex = Mutex()
    private val mediaDemandState = MediaDemandState()
    private val _mediaDemanded = MutableStateFlow(false)
    /** True while Telecom media is demanded (foreground PTT or VOX). */
    val mediaDemanded: StateFlow<Boolean> = _mediaDemanded.asStateFlow()
    private val _latencyCritical = MutableStateFlow(false)
    val latencyCritical: StateFlow<Boolean> = _latencyCritical.asStateFlow()
    @Volatile private var hasPendingLatencyMedia = false
    private val incomingMediaMutex = Mutex()
    private val pendingIncomingMedia = mutableMapOf<String, ArrayDeque<IncomingMediaEvent>>()
    /** Frames arriving while Telecom teardown still holds [IntercomTelecomSession.hasCall]. */
    private val pendingMediaPlayback = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private var audioPrepared = false
    private var lastFanOutDiagMs = 0L
    /** Pipeline Failed disconnects Telecom; block auto-reactivate until [retryAudioPipeline]. */
    @Volatile private var telecomBlockedUntilRetry = false

    private var started = false
    /** True while the mesh (beacon, TCP server, dial loops) is running. */
    val isStarted: Boolean get() = started
    private var localUid: String = ""
    private var displayName: String = ""
    private var knownCrewUids: Set<String> = emptySet()
    private val peerLanEndpoints = ConcurrentHashMap<String, LanFallbackEndpoint>()
    private val peerOverlayEndpoints = ConcurrentHashMap<String, OverlayEndpoint>()
    /** Cold-start probe hosts from the persisted roster; live sightings take precedence. */
    private val seededOverlayProbeHosts = ConcurrentHashMap<String, String>()
    @Volatile private var connectivitySnapshot = ConnectivitySnapshot()
    private val pathReconciliationSignal = Channel<Unit>(Channel.CONFLATED)

    private data class OverlayEndpoint(
        val host: String,
        val port: Int,
        val displayName: String,
    )

    init {
        this.telecomSession.setMediaLifecycleCallbacks(
            onInactive = { closeAudioForTelecomLifecycle() },
            onActive = { reconcileAudioForTelecomLifecycle() },
            onDisconnected = { preserveTransmission ->
                closeAudioForTelecomLifecycle(cancelTransmission = !preserveTransmission)
            },
        )
        capture.onRoutedDeviceChanged = { kind -> onObservedRoutingChanged("capture", kind) }
        playback.onRoutedDeviceChanged = { kind -> onObservedRoutingChanged("playback", kind) }
    }

    private var routingMismatchJob: Job? = null
    /** Platform routed the live pipeline off the confirmed BT endpoint (SCO never started). */
    private val _observedRouteMismatch = MutableStateFlow(false)
    val observedRouteMismatch: StateFlow<Boolean> = _observedRouteMismatch.asStateFlow()

    /**
     * Telecom believes the call is on Bluetooth but the platform routed the live
     * recorder/track elsewhere (typically SCO never started). One settle re-check,
     * then surface a diverged-style banner — audio keeps flowing on whatever device
     * the platform picked; the user gets a one-tap "This device" fix.
     */
    private fun onObservedRoutingChanged(source: String, kind: ObservedAudioDeviceKind) {
        if (kind == ObservedAudioDeviceKind.UNKNOWN) return
        if (!bluetoothRouteMismatch(kind)) {
            routingMismatchJob?.cancel()
            routingMismatchJob = null
            _observedRouteMismatch.value = false
            return
        }
        if (routingMismatchJob?.isActive == true) return
        routingMismatchJob = scope.launch(Dispatchers.IO) {
            delay(ROUTING_SETTLE_MS)
            val inputKind = capture.observedRoutedKind()
            val outputKind = playback.observedRoutedKind()
            val stillMismatched =
                (inputKind != null && bluetoothRouteMismatch(inputKind)) ||
                    (outputKind != null && bluetoothRouteMismatch(outputKind))
            if (stillMismatched) {
                Log.w(
                    TAG,
                    "$source routed to $kind while Bluetooth endpoint is confirmed " +
                        "(input=$inputKind output=$outputKind)",
                )
            }
            _observedRouteMismatch.value = stillMismatched
        }
    }

    private fun bluetoothRouteMismatch(kind: ObservedAudioDeviceKind): Boolean {
        if (kind == ObservedAudioDeviceKind.UNKNOWN) return false
        val call = telecomSession.currentState
        return call.mediaActive &&
            call.outputKind == OutputKind.BLUETOOTH &&
            _audioPipelineState.value is AudioPipelineState.Ready &&
            kind != ObservedAudioDeviceKind.BLUETOOTH
    }

    val statusText: StateFlow<String> = combine(
        combine(
            _activeRecipientUids,
            _peerMetrics,
            beacon.peers,
            _audioPipelineState,
            routeReady,
        ) { active, metrics, peers, pipeline, duplexReady ->
            describeStatus(
                active = active ?: emptySet(),
                metrics = metrics ?: emptyMap(),
                visiblePeerCount = peers.count { it.uid != localUid },
                pipeline = pipeline,
                duplexReady = duplexReady,
            )
        },
        beacon.bindFailed,
    ) { status, bindFailed ->
        if (bindFailed) {
            "$status · ${appContext.getString(R.string.status_discovery_unavailable)}"
        } else {
            status
        }
    }.stateIn(scope, SharingStarted.Eagerly, appContext.getString(R.string.status_searching_crewmates))

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

        val restoreVoxEnabled = prefs.getBoolean(KEY_VOX_ENABLED, false)
        if (restoreVoxEnabled) {
            pttPolicy.cancel()
            activePolicy = voxPolicy
            _voxEnabled.value = true
        }
        mediaDemandState.setVoxEnabled(restoreVoxEnabled)
        mediaDemandState.setSessionActive(true)
        watchPolicy(activePolicy)

        lifecycleJobs += scope.launch {
            for (ignored in pathReconciliationSignal) {
                if (started) reconcilePeerPaths()
            }
        }
        lifecycleJobs += scope.launch {
            beacon.presence.collect { requestPathReconciliation() }
        }
        lifecycleJobs += scope.launch {
            networkMonitor.connectivity.collect { onConnectivitySnapshot(it) }
        }
        lifecycleJobs += scope.launch(Dispatchers.IO) {
            telecomSession.callTornDown.collect { flushPendingMediaPlayback() }
        }
        networkMonitor.start()

        loadPersistedActiveRecipients()
        requestPathReconciliation()
        ensureAudioRoutingMonitor()
        scheduleMediaDemandReconciliation()
    }

    fun syncCrewPeers(crewUids: Set<String>) {
        val previousKnown = knownCrewUids
        knownCrewUids = crewUids
        val active = ActiveRecipientPolicy.recipientsAfterCrewSync(
            currentActive = _activeRecipientUids.value,
            crewUids = crewUids,
            previousKnownCrew = previousKnown,
            optInMode = optInRecipients,
        )
        if (active != _activeRecipientUids.value) {
            setActiveRecipients(active, persist = true)
        }
        val prunedActive = if (crewUids.isEmpty() && optInRecipients) {
            _activeRecipientUids.value
        } else {
            _activeRecipientUids.value.filter { it in crewUids }.toSet()
        }
        if (prunedActive != _activeRecipientUids.value) {
            setActiveRecipients(prunedActive, persist = true)
        }
        val targetUids = (crewUids + _activeRecipientUids.value).filter { it != localUid }.toSet()
        targetUids.forEach { uid -> ensureConnection(uid).start() }
        if (crewUids.isNotEmpty()) {
            val removed = connections.keys - targetUids
            removed.forEach { removeConnection(it) }
        }
        requestPathReconciliation()
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

    fun removeRecipient(uid: String) {
        if (uid == localUid) return
        if (uid !in _activeRecipientUids.value) return
        setActiveRecipients(_activeRecipientUids.value - uid, persist = true)
    }

    fun setActiveRecipients(uids: Set<String>, persist: Boolean = false) {
        val filtered = uids.filter { it != localUid }.toSet()
        val previous = _activeRecipientUids.value
        val added = filtered - previous
        val removed = previous - filtered
        _activeRecipientUids.value = filtered
        if (persist) saveActiveRecipients(filtered)
        filtered.forEach {
            ensureConnection(it).apply {
                start()
                requestDialReconciliation()
            }
        }
        if (isOutboundMediaActive()) {
            added.forEach { uid -> connections[uid]?.sendMediaActivity(true) }
            removed.forEach { uid -> connections[uid]?.sendMediaActivity(false) }
        }
        requestPathReconciliation()
    }

    fun onMicrophonePermissionGranted() {
        if (mediaDemandState.setMicrophonePermissionGranted(true)) {
            scheduleMediaDemandReconciliation()
        }
        if (!started) return
        ensureAudioRoutingMonitor()
    }

    fun onMicrophonePermissionDenied() {
        // Reconcile demand so the Wi-Fi low-latency lock is released along with Telecom.
        if (mediaDemandState.setMicrophonePermissionGranted(false)) {
            scheduleMediaDemandReconciliation()
        }
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
            // VOX uses Telecom duplex; tear down multimedia inbound if we were backgrounded.
            mediaInboundPlayer.stop()
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
        if (foreground) {
            // Foreground restores the Telecom path; multimedia inbound must not keep playing.
            mediaInboundPlayer.stop()
            scope.launch(Dispatchers.IO) {
                incomingMediaMutex.withLock {
                    pendingMediaPlayback.clear()
                    refreshPendingLatencyMediaLocked()
                }
            }
        } else if (!_voxEnabled.value) {
            pttPolicy.cancel()
            setOutboundMediaActive(false)
            // Stale remote demand must not reopen Telecom after MEDIA handoff.
            if (mediaDemandState.clearRemoteDemand()) {
                // Demand formula unchanged (still FG/VOX); clear only remote bookkeeping.
            }
        }
        if (mediaDemandState.setAppForeground(foreground)) {
            scheduleMediaDemandReconciliation()
        }
        if (!foreground && !_voxEnabled.value) {
            // Serialize TELECOM→MEDIA: finish disconnect before inbound may take MEDIA focus.
            scope.launch(Dispatchers.IO) {
                mediaDemandMutex.withLock {
                    reconcileMediaDemandUnlocked()
                }
            }
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
        _activeRecipientUids.value.forEach { connections[it]?.requestDialReconciliation() }
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
        scope.launch(Dispatchers.IO) {
            telecomBlockedUntilRetry = false
            _observedRouteMismatch.value = false
            telecomSession.selectAudioRoute(key)
            reconcileMediaDemand()
        }
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
        routingMismatchJob?.cancel()
        routingMismatchJob = null
        _observedRouteMismatch.value = false
        _audioPipelineState.value = state
        if (cancelTransmission) {
            setOutboundMediaActive(false)
        }
    }

    private fun failAudioPathLocked(reason: String) {
        Log.e(TAG, "duplex pipeline failed: $reason")
        // Keep demand for Retry UI, but drop the ACTIVE call so music is not held on silence.
        closeAudioPathLocked(AudioPipelineState.Failed(reason))
        telecomBlockedUntilRetry = true
        scope.launch(Dispatchers.IO) {
            telecomSession.disconnect()
        }
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
            telecomBlockedUntilRetry = false
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
        refreshLatencyCritical()
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

    private fun mediaDemanded(): Boolean {
        val demanded = mediaDemandState.isDemanded()
        _mediaDemanded.value = demanded
        return demanded
    }

    private fun scheduleMediaDemandReconciliation() {
        scope.launch(Dispatchers.IO) { reconcileMediaDemand() }
    }

    private suspend fun reconcileMediaDemand() {
        mediaDemandMutex.withLock {
            reconcileMediaDemandUnlocked()
        }
    }

    private suspend fun reconcileMediaDemandUnlocked() {
        while (true) {
            val demanded = mediaDemanded()
            if (demanded && telecomBlockedUntilRetry) return
            when (telecomDemandAction(demanded, telecomSession.isActive, telecomSession.hasCall)) {
                TelecomDemandAction.NONE -> return
                TelecomDemandAction.ACTIVATE -> {
                    if (!telecomSession.activate()) {
                        // Keep demand; leave Failed visible. Retry or a later demand
                        // transition re-enters activate via reconcile — no stop()/poison.
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
                networkSocketBinder = networkMonitor,
                inboundRouteResolver = { socket ->
                    classifyAcceptedSocket(socket, connectivitySnapshot)
                },
                isStillWanted = { peerUid in knownCrewUids || peerUid in _activeRecipientUids.value },
                overlayPeerProvider = {
                    if (overlayFallbackEnabled) {
                        overlayPeerFor(
                            peerUid,
                            beacon.presence.value.overlaySightings[peerUid]
                                ?.takeIf { it.uid != localUid },
                        )?.let { routePeer(it, connectivitySnapshot) }
                    } else {
                        null
                    }
                },
                lanPeerProvider = {
                    lanPeerFor(peerUid, beacon.presence.value.lanSightings[peerUid])
                        ?.let { routePeer(it, connectivitySnapshot) }
                },
                onOverlayEndpointDead = { uid -> forgetOverlayEndpoint(uid) },
            )
            startAudioCollection(conn)
            startConnectionFeedback(conn)
            startMetricsWatch(conn)
            conn
        }
    }

    private fun ensureKnownPeer(peerUid: String) {
        if (peerUid == localUid) return
        knownCrewUids = knownCrewUids + peerUid
        val conn = ensureConnection(peerUid)
        conn.start()
        requestPathReconciliation()
    }

    private fun removeConnection(peerUid: String) {
        audioCollectJobs.remove(peerUid)?.cancel()
        feedbackWatchJobs.remove(peerUid)?.cancel()
        metricsWatchJobs.remove(peerUid)?.cancel()
        _peerMetrics.update { it - peerUid }
        connections.remove(peerUid)?.stop()
        refreshBeaconConnectedPeers()
        setRemoteTelecomDemand(peerUid, false)
        setLatencyRemoteActive(peerUid, false)
        scope.launch(Dispatchers.IO) {
            incomingMediaMutex.withLock {
                pendingIncomingMedia.remove(peerUid)
                pendingMediaPlayback.remove(peerUid)
                refreshPendingLatencyMediaLocked()
            }
        }
        receivingUntilMs.remove(peerUid)
        refreshReceivingUids()
        refreshLatencyCritical()
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
                    setLatencyRemoteActive(conn.peerUid, false)
                    incomingMediaMutex.withLock {
                        pendingIncomingMedia.remove(conn.peerUid)
                        refreshPendingLatencyMediaLocked()
                    }
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
                refreshBeaconConnectedPeers()
                requestPathReconciliation()
            }
        }
    }

    private fun refreshBeaconConnectedPeers() {
        beacon.setConnectedPeers(
            connections.values.mapNotNull { connection ->
                val connected = connection.linkState.value as? PeerLink.LinkState.Connected
                    ?: return@mapNotNull null
                connection.peerUid to (connected.via == PathLabels.VPN)
            }.toMap(),
        )
    }

    private fun startAudioCollection(conn: PeerConnection) {
        if (audioCollectJobs.containsKey(conn.peerUid)) return
        audioCollectJobs[conn.peerUid] = scope.launch(Dispatchers.IO) {
            conn.peerLink.incomingMedia.collect { event -> handleIncomingMedia(conn.peerUid, event) }
        }
    }

    private fun inboundPlaybackMode(): InboundPlaybackMode =
        InboundPlaybackPolicy.mode(
            appForeground = _appForeground.value,
            voxEnabled = _voxEnabled.value,
        )

    private suspend fun handleIncomingMedia(peerUid: String, event: IncomingMediaEvent) {
        incomingMediaMutex.withLock {
            if (event is IncomingMediaEvent.Activity) {
                setLatencyRemoteActive(peerUid, event.active)
            }
            if (inboundPlaybackMode() == InboundPlaybackMode.MEDIA) {
                handleIncomingMediaAsMultimediaLocked(peerUid, event)
                return
            }
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
                        playIncomingTelecomLocked(peerUid, event.payload)
                    } else {
                        setRemoteTelecomDemand(peerUid, true)
                        enqueueIncomingLocked(peerUid, event)
                    }
                }
            }
        }
    }

    /**
     * Background + VOX off: play on [MediaInboundPlayer] without waking Telecom.
     * Activity events only drive receiving UI; audio frames play immediately.
     */
    private fun handleIncomingMediaAsMultimediaLocked(peerUid: String, event: IncomingMediaEvent) {
        // Drop any Telecom-pending queue for this peer — that path is not used here.
        pendingIncomingMedia.remove(peerUid)
        refreshPendingLatencyMediaLocked()
        when (event) {
            is IncomingMediaEvent.Activity -> {
                if (!event.active) {
                    receivingUntilMs.remove(peerUid)
                    refreshReceivingUids()
                    refreshLatencyCritical()
                }
                // Do not call setRemoteTelecomDemand — remote peers must not reopen Telecom.
            }
            is IncomingMediaEvent.Audio -> {
                playIncomingMediaLocked(peerUid, event.payload)
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
        refreshPendingLatencyMediaLocked()
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
                                if (!playIncomingTelecomLocked(peerUid, event.payload)) break
                            }
                            is IncomingMediaEvent.Activity ->
                                setRemoteTelecomDemand(peerUid, event.active)
                        }
                    }
                    if (queue.isEmpty()) pendingIncomingMedia.remove(peerUid)
                }
                refreshPendingLatencyMediaLocked()
            }
        }
    }

    private fun playIncomingTelecomLocked(peerUid: String, payload: ByteArray): Boolean {
        if (!playback.play(payload)) {
            if (_audioPipelineState.value is AudioPipelineState.Ready) {
                onAudioPipelineFailure("AudioTrack could not play a received frame")
            }
            return false
        }
        markReceiving(peerUid)
        return true
    }

    private fun playIncomingMediaLocked(peerUid: String, payload: ByteArray): Boolean {
        // Gate MEDIA focus until Telecom call is fully gone (FG→BG serialization).
        // Buffer the speech onset instead of dropping it; callTornDown flushes it.
        if (telecomSession.hasCall) {
            val queue = pendingMediaPlayback.getOrPut(peerUid) { ArrayDeque() }
            while (queue.size >= MAX_PENDING_MEDIA_FRAMES) queue.removeFirst()
            queue.addLast(payload)
            refreshPendingLatencyMediaLocked()
            return false
        }
        flushPendingMediaPlaybackLocked(peerUid)
        if (!mediaInboundPlayer.play(payload)) return false
        markReceiving(peerUid)
        return true
    }

    /** Event-driven flush once Telecom teardown completes ([IntercomTelecomSession.callTornDown]). */
    private suspend fun flushPendingMediaPlayback() {
        incomingMediaMutex.withLock {
            if (telecomSession.hasCall) return
            if (inboundPlaybackMode() != InboundPlaybackMode.MEDIA) {
                pendingMediaPlayback.clear()
                refreshPendingLatencyMediaLocked()
                return
            }
            pendingMediaPlayback.keys.toList().forEach { flushPendingMediaPlaybackLocked(it) }
        }
    }

    private fun flushPendingMediaPlaybackLocked(peerUid: String) {
        val queue = pendingMediaPlayback.remove(peerUid) ?: return
        refreshPendingLatencyMediaLocked()
        var played = false
        queue.forEach { payload ->
            if (mediaInboundPlayer.play(payload)) played = true
        }
        if (played) markReceiving(peerUid)
    }

    private fun markReceiving(peerUid: String) {
        receivingUntilMs[peerUid] = System.currentTimeMillis() + RECEIVING_IDLE_MS
        refreshReceivingUids()
        refreshLatencyCritical()
        ensureReceivingExpiryJob()
    }

    /**
     * Event-driven replacement for the old 200 ms polling sweep: one job sleeps until
     * the earliest receiving deadline and exits when nothing is receiving.
     */
    private fun ensureReceivingExpiryJob() {
        synchronized(receivingExpiryLock) {
            if (receivingSweepJob?.isActive == true) return
            receivingSweepJob = scope.launch { receivingExpiryLoop() }
        }
    }

    private suspend fun receivingExpiryLoop() {
        while (currentCoroutineContext().isActive) {
            val next = receivingUntilMs.values.minOrNull()
            if (next == null) {
                val exit = synchronized(receivingExpiryLock) {
                    receivingUntilMs.isEmpty().also { empty ->
                        if (empty) receivingSweepJob = null
                    }
                }
                if (exit) return
                continue
            }
            delay((next - System.currentTimeMillis()).coerceAtLeast(20L))
            val now = System.currentTimeMillis()
            receivingUntilMs.entries.removeIf { it.value <= now }
            refreshReceivingUids()
            refreshLatencyCritical()
        }
    }

    private fun refreshReceivingUids() {
        val now = System.currentTimeMillis()
        val active = receivingUntilMs.filterValues { it > now }.keys
        _receivingFromUids.value = active
    }

    private fun setLatencyRemoteActive(peerUid: String, active: Boolean) {
        if (active) latencyRemotePeers.add(peerUid) else latencyRemotePeers.remove(peerUid)
        refreshLatencyCritical()
    }

    /** Caller holds [incomingMediaMutex]. */
    private fun refreshPendingLatencyMediaLocked() {
        hasPendingLatencyMedia = pendingIncomingMedia.values.any { it.isNotEmpty() } ||
            pendingMediaPlayback.values.any { it.isNotEmpty() }
        refreshLatencyCritical()
    }

    private fun refreshLatencyCritical() {
        _latencyCritical.value = latencyCriticalState(
            outbound = mediaDemandState.isOutbound(),
            remoteActive = latencyRemotePeers.isNotEmpty(),
            receiving = receivingUntilMs.isNotEmpty(),
            pending = hasPendingLatencyMedia,
        )
    }

    private fun requestPathReconciliation() {
        pathReconciliationSignal.trySend(Unit)
    }

    private fun reconcilePeerPaths() {
        val snapshot = beacon.presence.value
        val nowMs = System.currentTimeMillis()
        val lanByUid = snapshot.lanSightings.filterKeys { it != localUid }
        val overlaySightings = snapshot.overlaySightings.filterKeys { it != localUid }

        lanByUid.values.forEach { rememberLanEndpoint(it) }
        lanByUid.values.forEach { rememberOverlayEndpoint(it) }
        overlaySightings.values.forEach { rememberOverlayEndpoint(it) }

        val visibleUids = lanByUid.keys + overlaySightings.keys
        val relevantUids = (knownCrewUids + _activeRecipientUids.value + visibleUids)
            .filter { it != localUid }
            .toSet()

        relevantUids.forEach { uid ->
            val lan = lanPeerFor(uid, lanByUid[uid])
                ?.let { routePeer(it, connectivitySnapshot) }
            val overlay = overlayPeerFor(uid, overlaySightings[uid])
                ?.let { routePeer(it, connectivitySnapshot) }
            val conn = ensureConnection(uid)
            if (lan != null || overlay != null) {
                conn.start()
            }
            conn.applyPathTargets(lan, overlay, nowMs)
        }

        if (overlayFallbackEnabled) {
            updateOverlayProbes(lanByUid.keys)
        }
    }

    private fun overlayPeerFor(uid: String, sighting: LanPeer?): LanPeer? {
        sighting?.let { return it.copy(viaOverlay = true) }
        val endpoint = peerOverlayEndpoints[uid] ?: return null
        // Registry entry — not a live sighting (do not stamp lastSeenMs = now).
        return LanPeer(
            uid = uid,
            displayName = endpoint.displayName,
            host = endpoint.host,
            port = endpoint.port,
            lastSeenMs = 0L,
            overlayHost = endpoint.host,
            viaOverlay = true,
        )
    }

    private fun lanPeerFor(uid: String, sighting: LanPeer?): LanPeer? {
        return lanPeerForFallback(uid, sighting, peerLanEndpoints[uid])
    }

    private fun rememberLanEndpoint(peer: LanPeer) {
        if (peer.viaOverlay || peer.host.isBlank() || peer.port <= 0) return
        peerLanEndpoints[peer.uid] = LanFallbackEndpoint(
            host = peer.host,
            port = peer.port,
            displayName = peer.displayName,
        )
    }

    private fun rememberOverlayEndpoint(peer: LanPeer) {
        val overlayHost = peer.overlayHost
            ?: peer.host.takeIf { TailscaleInterface.isCgnatAddress(peer.host) }
        if (overlayHost.isNullOrBlank() || peer.port <= 0) return
        peerOverlayEndpoints[peer.uid] = OverlayEndpoint(
            host = overlayHost,
            port = peer.port,
            displayName = peer.displayName,
        )
    }

    /** Drop sticky overlay host:port so UDP probes can rediscover a fresh listen port. */
    fun forgetOverlayEndpoint(uid: String) {
        if (peerOverlayEndpoints.remove(uid) != null) {
            Log.i(TAG, "forgot overlay endpoint for $uid")
            requestPathReconciliation()
        }
    }

    /**
     * Known crew that only ever met this device on Tailscale would otherwise be
     * unreachable until the next LAN encounter. UDP probes go to the fixed beacon
     * port, so a stale TCP port in the cache does not matter.
     */
    fun seedOverlayProbeHosts(hosts: Map<String, String>) {
        hosts.forEach { (uid, host) ->
            if (uid.isNotBlank() && host.isNotBlank()) seededOverlayProbeHosts[uid] = host
        }
        requestPathReconciliation()
    }

    /** Probe overlay only for peers without a live LAN sighting. */
    private fun updateOverlayProbes(lanVisibleUids: Set<String>) {
        val connectedUids = connections.values.mapNotNullTo(mutableSetOf()) { connection ->
            connection.peerUid.takeIf {
                connection.linkState.value is PeerLink.LinkState.Connected
            }
        }
        val targets = overlayProbeTargets(
            overlayAvailable = connectivitySnapshot.overlayNetwork != null,
            localUid = localUid,
            relevantUids = knownCrewUids + _activeRecipientUids.value,
            lanVisibleUids = lanVisibleUids,
            connectedUids = connectedUids,
            endpointHosts = peerOverlayEndpoints.mapValues { it.value.host },
            seededHosts = seededOverlayProbeHosts,
        )
        beacon.setOverlayProbeTargets(targets)
    }

    private fun onConnectivitySnapshot(next: ConnectivitySnapshot) {
        if (!started) return
        val previous = connectivitySnapshot
        if (next == previous) return
        connectivitySnapshot = next

        val invalidation = connectivityInvalidation(previous, next)
        val invalidatedHandles = invalidation.lanHandles + invalidation.overlayHandles

        beacon.updateOverlayNetwork(if (overlayFallbackEnabled) next.overlayNetwork else null)
        if (invalidation.lanHandles.isNotEmpty()) {
            beacon.removeInvalidLanSightings { peer -> routePeer(peer, next) != null }
            peerLanEndpoints.entries.removeIf { (_, endpoint) ->
                routePeer(endpoint.toLanPeer(uid = "_probe"), next) == null
            }
        }
        if (invalidation.overlayHandles.isNotEmpty()) beacon.clearOverlaySightings()
        if (invalidatedHandles.isNotEmpty()) {
            connections.values.forEach { it.onNetworksInvalidated(invalidatedHandles) }
        }
        if (previous.lanNetworks != next.lanNetworks) beacon.requestAnnouncement()
        requestPathReconciliation()
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
            isReceiving = { isReceiving.value },
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
                // PTT outbound must always mirror shouldTransmit (including release while Ready).
                setOutboundMediaActive(transmitting)
            }
        }
    }

    private fun loadPersistedActiveRecipients() {
        val persisted = runCatching {
            val raw = prefs.getString(KEY_ACTIVE_RECIPIENTS, null) ?: return
            json.decodeFromString<Set<String>?>(raw)
        }.getOrNull() ?: return
        val filtered = persisted.filter { it.isNotBlank() && it != localUid }.toSet()
        if (filtered.isEmpty()) return
        setActiveRecipients(filtered, persist = false)
    }

    private fun saveActiveRecipients(uids: Set<String>) {
        prefs.edit().putString(KEY_ACTIVE_RECIPIENTS, json.encodeToString(uids)).apply()
    }

    /** Event-driven metrics: one collector per connection, no periodic polling. */
    private fun startMetricsWatch(conn: PeerConnection) {
        if (metricsWatchJobs.containsKey(conn.peerUid)) return
        metricsWatchJobs[conn.peerUid] = scope.launch(Dispatchers.Default) {
            combine(conn.linkState, conn.rttMs, conn.backlogMs) { state, rtt, backlog ->
                PeerMetrics(
                    rttMs = rtt,
                    pathLabel = (state as? PeerLink.LinkState.Connected)?.via,
                    backlogMs = backlog,
                    linkState = state,
                )
            }.collect { metrics ->
                _peerMetrics.update { it + (conn.peerUid to metrics) }
            }
        }
    }

    private fun describeStatus(
        active: Set<String>,
        metrics: Map<String, PeerMetrics>,
        visiblePeerCount: Int,
        pipeline: AudioPipelineState,
        duplexReady: Boolean,
    ): String {
        val audioClause = when {
            pipeline is AudioPipelineState.Failed ->
                appContext.getString(R.string.status_audio_unavailable)
            !duplexReady && (_appForeground.value || _voxEnabled.value) ->
                appContext.getString(R.string.status_audio_pending)
            else -> null
        }
        val included = active.filter { it in knownCrewUids }.toSet()
        if (optInRecipients) {
            return when {
                visiblePeerCount == 0 && knownCrewUids.isEmpty() ->
                    appContext.getString(R.string.status_searching_crewmates)
                included.isEmpty() ->
                    appContext.resources.getQuantityString(
                        R.plurals.status_crewmates_nearby_none_included,
                        visiblePeerCount,
                        visiblePeerCount,
                    ).withAudioClause(audioClause)
                else -> {
                    val connected = included.count { uid ->
                        metrics[uid]?.linkState is PeerLink.LinkState.Connected
                    }
                    val pathCounts = included.mapNotNull { metrics[it]?.pathLabel }.groupingBy { it }.eachCount()
                    val pathSummary = pathCounts.entries.joinToString(" · ") { (path, count) ->
                        "$count ${PathLabels.displayName(appContext, path)}"
                    }
                    buildString {
                        append(
                            appContext.resources.getQuantityString(
                                R.plurals.status_included,
                                included.size,
                                included.size,
                            ),
                        )
                        append(" · ")
                        append(
                            appContext.resources.getQuantityString(
                                R.plurals.status_connected,
                                connected,
                                connected,
                            ),
                        )
                        if (pathSummary.isNotBlank()) {
                            append(" · ")
                            append(pathSummary)
                        }
                    }.withAudioClause(audioClause)
                }
            }
        }

        if (included.isEmpty()) {
            return if (knownCrewUids.isEmpty()) {
                appContext.getString(R.string.status_searching_crewmates)
            } else {
                appContext.getString(R.string.status_no_active_recipient)
                    .withAudioClause(audioClause)
            }
        }
        val connected = included.count { uid ->
            metrics[uid]?.linkState is PeerLink.LinkState.Connected
        }
        val pathCounts = included.mapNotNull { metrics[it]?.pathLabel }.groupingBy { it }.eachCount()
        val pathSummary = pathCounts.entries.joinToString(" · ") { (path, count) ->
            "$count ${PathLabels.displayName(appContext, path)}"
        }
        return buildString {
            append(
                appContext.resources.getQuantityString(
                    R.plurals.status_active,
                    included.size,
                    included.size,
                ),
            )
            append(" · ")
            append(
                appContext.resources.getQuantityString(
                    R.plurals.status_connected,
                    connected,
                    connected,
                ),
            )
            if (pathSummary.isNotBlank()) {
                append(" · ")
                append(pathSummary)
            }
        }.withAudioClause(audioClause)
    }

    private fun String.withAudioClause(clause: String?): String =
        if (clause.isNullOrBlank()) this else "$this · $clause"

    fun releaseAudioSession() {
        routeWatchJob?.cancel()
        routeWatchJob = null
        pttPolicy.cancel()
        stopVoxCapture()
        setOutboundMediaActive(false)
        mediaDemandState.endSession()
        latencyRemotePeers.clear()
        hasPendingLatencyMedia = false
        _latencyCritical.value = false
        mediaInboundPlayer.stop()
        playback.stop()
        telecomSession.stop()
        audioPrepared = false
        _audioPipelineState.value = AudioPipelineState.Closed
    }

    /** Terminal app shutdown: release every media, transport and discovery resource. */
    fun shutdown() {
        if (!started) {
            releaseAudioSession()
            return
        }
        releaseAudioSession()
        started = false
        _appForeground.value = false

        policyWatchJob?.cancel()
        policyWatchJob = null
        receivingSweepJob?.cancel()
        receivingSweepJob = null
        metricsWatchJobs.values.forEach { it.cancel() }
        metricsWatchJobs.clear()
        lifecycleJobs.forEach { it.cancel() }
        lifecycleJobs.clear()

        audioCollectJobs.values.forEach { it.cancel() }
        audioCollectJobs.clear()
        feedbackWatchJobs.values.forEach { it.cancel() }
        feedbackWatchJobs.clear()
        connections.values.forEach { it.stop() }
        connections.clear()

        beacon.stop()
        lanServer.onUnknownInboundPeer = null
        lanServer.stop()
        networkMonitor.stop()
        connectivitySnapshot = ConnectivitySnapshot()
        while (pathReconciliationSignal.tryReceive().isSuccess) Unit

        peerOverlayEndpoints.clear()
        seededOverlayProbeHosts.clear()

        receivingUntilMs.clear()
        latencyRemotePeers.clear()
        hasPendingLatencyMedia = false
        _receivingFromUids.value = emptySet()
        _peerMetrics.value = emptyMap()
        _isTransmitting.value = false
        _latencyCritical.value = false
        knownCrewUids = emptySet()
        peerLanEndpoints.clear()
    }

    companion object {
        private const val TAG = "LanIntercomEngine"
        private const val RECEIVING_IDLE_MS = 500L
        /** Grace before treating a BT routing mismatch as pipeline failure (SCO warm-up). */
        private const val ROUTING_SETTLE_MS = 1_500L
        private const val MAX_PENDING_INCOMING_EVENTS = 250
        /** ~2 s of 20 ms Opus frames buffered across the Telecom teardown gap. */
        private const val MAX_PENDING_MEDIA_FRAMES = 100
        private const val PREFS_NAME = "voxcrew_lanlink"
        private const val KEY_ACTIVE_RECIPIENTS = "active_recipient_uids"
        private const val KEY_VOX_ENABLED = "vox_enabled"
        private const val KEY_VOX_SENSITIVITY = "vox_sensitivity"
    }
}

internal fun latencyCriticalState(
    outbound: Boolean,
    remoteActive: Boolean,
    receiving: Boolean,
    pending: Boolean,
): Boolean = outbound || remoteActive || receiving || pending

internal data class ConnectivityInvalidation(
    val lanHandles: Set<Long> = emptySet(),
    val overlayHandles: Set<Long> = emptySet(),
)

internal fun connectivityInvalidation(
    previous: ConnectivitySnapshot,
    next: ConnectivitySnapshot,
): ConnectivityInvalidation {
    val oldLan = previous.lanNetworks.associateBy { it.networkHandle }
    val newLan = next.lanNetworks.associateBy { it.networkHandle }
    val invalidLan = oldLan.keys.filterTo(mutableSetOf()) { handle ->
        newLan[handle] != oldLan[handle]
    }
    val oldOverlay = previous.overlayNetwork
    val invalidOverlay = if (oldOverlay != null && oldOverlay != next.overlayNetwork) {
        setOf(oldOverlay.networkHandle)
    } else {
        emptySet()
    }
    return ConnectivityInvalidation(invalidLan, invalidOverlay)
}

internal fun overlayProbeTargets(
    overlayAvailable: Boolean,
    localUid: String,
    relevantUids: Set<String>,
    lanVisibleUids: Set<String>,
    connectedUids: Set<String>,
    endpointHosts: Map<String, String>,
    seededHosts: Map<String, String>,
): Map<String, String> {
    if (!overlayAvailable) return emptyMap()
    return relevantUids
        .asSequence()
        .filter { it != localUid && it !in lanVisibleUids && it !in connectedUids }
        .mapNotNull { uid -> (endpointHosts[uid] ?: seededHosts[uid])?.let { uid to it } }
        .toMap()
}

internal data class LanFallbackEndpoint(
    val host: String,
    val port: Int,
    val displayName: String,
) {
    fun toLanPeer(uid: String): LanPeer = LanPeer(
        uid = uid,
        displayName = displayName,
        host = host,
        port = port,
        lastSeenMs = 0L,
        viaOverlay = false,
    )
}

internal fun lanPeerForFallback(
    uid: String,
    sighting: LanPeer?,
    fallback: LanFallbackEndpoint?,
): LanPeer? {
    sighting?.takeUnless { it.viaOverlay }?.let { return it }
    return fallback?.toLanPeer(uid)
}
