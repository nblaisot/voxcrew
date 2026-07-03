package com.nblaisot.voxcrew.connectivity.orchestration

import com.nblaisot.voxcrew.connectivity.discovery.LocalPeerDiscovery
import com.nblaisot.voxcrew.connectivity.local.LocalSignalingServer
import com.nblaisot.voxcrew.connectivity.model.ConnectivityThresholds
import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.connectivity.model.TransportMode
import com.nblaisot.voxcrew.connectivity.quality.PeerPathEvaluator
import com.nblaisot.voxcrew.connectivity.quality.PeerPathEvaluatorImpl
import com.nblaisot.voxcrew.connectivity.state.ConnectivityDiagnostics
import com.nblaisot.voxcrew.connectivity.state.ConnectivityState
import com.nblaisot.voxcrew.connectivity.state.TransportPreference
import com.nblaisot.voxcrew.connectivity.transport.SignalingTransport
import com.nblaisot.voxcrew.connectivity.transport.SignalingTransportKind
import com.nblaisot.voxcrew.connectivity.webrtc.ManagedPeerConnection
import com.nblaisot.voxcrew.connectivity.webrtc.WebRtcConnectionSwitcher
import com.nblaisot.voxcrew.connectivity.webrtc.isReadyForPromotion
import com.nblaisot.voxcrew.signaling.ConnectionState
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

class ConnectivityOrchestratorImpl(
    private val scope: CoroutineScope,
    private val localTransport: SignalingTransport,
    private val cloudTransport: SignalingTransport,
    private val localDiscovery: LocalPeerDiscovery,
    private val localServer: LocalSignalingServer?,
    private val pathEvaluator: PeerPathEvaluator = PeerPathEvaluatorImpl(),
    private val connectionSwitcher: WebRtcConnectionSwitcher,
    private val thresholds: ConnectivityThresholds = ConnectivityThresholds(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val isHostProvider: () -> Boolean = { false },
    private val enableAutoEvaluation: Boolean = true,
) : ConnectivityOrchestrator {
    private val _state = MutableStateFlow<ConnectivityState>(ConnectivityState.Idle)
    override val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(ConnectivityDiagnostics())
    override val diagnostics: StateFlow<ConnectivityDiagnostics> = _diagnostics.asStateFlow()

    private val _relayed = MutableSharedFlow<SignalingEnvelope>(extraBufferCapacity = 64)
    override val relayedSignaling: SharedFlow<SignalingEnvelope> = _relayed.asSharedFlow()

    private var session: SessionDescriptor? = null
    private var preference = TransportPreference.AUTO
    private var evaluateJob: Job? = null
    private var lastSwitchAtMs = 0L
    private var reconnectAttempt = 0
    private var localGeneration: GenerationId? = null
    private var cloudGeneration: GenerationId? = null
    private var candidateGeneration: GenerationId? = null
    private var activeTransportMode = TransportMode.NONE
    private var cloudControlConnected = false

    override suspend fun beginSession(
        descriptor: SessionDescriptor,
        preference: TransportPreference,
    ) {
        session = descriptor
        this.preference = preference
        reconnectAttempt = 0
        _state.value = ConnectivityState.Discovering
        updateDiagnostics()
        localDiscovery.start(descriptor.sessionId)
        if (descriptor.isLocalHost && localServer != null) {
            val info = localServer.start(descriptor.sessionId)
            _diagnostics.update { it.copy(localAddress = "${info.host}:${info.port}") }
        }
        startEvaluationLoop()
        evaluateNow()
    }

    override suspend fun endSession() {
        evaluateJob?.cancel()
        localDiscovery.stop()
        localServer?.stop()
        localGeneration?.let { localTransport.disconnect(it) }
        cloudGeneration?.let { cloudTransport.disconnect(it) }
        connectionSwitcher.closeAll()
        session = null
        localGeneration = null
        cloudGeneration = null
        candidateGeneration = null
        activeTransportMode = TransportMode.NONE
        _state.value = ConnectivityState.Idle
        _diagnostics.value = ConnectivityDiagnostics()
    }

    override fun setTransportPreference(preference: TransportPreference) {
        this.preference = preference
    }

    override suspend fun relayWebRtc(envelope: SignalingEnvelope) {
        val transport = activeTransport() ?: cloudTransport
        transport.send(envelope)
    }

    override suspend fun evaluateNow() {
        val s = session ?: return
        val now = clock()
        probePaths(now)
        when (val current = _state.value) {
            ConnectivityState.Idle -> Unit
            ConnectivityState.Discovering -> handleDiscovering(s, now)
            is ConnectivityState.ConnectingLocal -> handleConnectingLocal(current, s, now)
            is ConnectivityState.LocalActive -> handleLocalActive(current, s, now)
            is ConnectivityState.ConnectingCloud -> handleConnectingCloud(current, s, now)
            is ConnectivityState.CloudActive -> handleCloudActive(current, s, now)
            is ConnectivityState.TransitioningToLocal -> handleTransitioningToLocal(current, s, now)
            is ConnectivityState.TransitioningToCloud -> handleTransitioningToCloud(current, s, now)
            is ConnectivityState.Reconnecting -> handleReconnecting(now)
            is ConnectivityState.Failed -> Unit
        }
        updateDiagnostics()
    }

    private suspend fun handleDiscovering(s: SessionDescriptor, now: Long) {
        when (preference) {
            TransportPreference.FORCE_CLOUD -> startCloudConnect(s)
            TransportPreference.FORCE_LOCAL -> startLocalConnect(s)
            TransportPreference.AUTO -> {
                val localAvailable = s.isLocalHost ||
                    localDiscovery.discoveredPeers.value.isNotEmpty() ||
                    s.sessionSecret != null
                if (localAvailable) startLocalConnect(s) else startCloudConnect(s)
            }
        }
    }

    private suspend fun handleConnectingLocal(current: ConnectivityState.ConnectingLocal, s: SessionDescriptor, now: Long) {
        val quality = pathEvaluator.current(TransportMode.LOCAL_LAN)
        if (quality.isStableForHandover(thresholds)) {
            _state.value = ConnectivityState.LocalActive(current.generation, quality)
            activeTransportMode = TransportMode.LOCAL_LAN
            recordSwitch("local_active", now)
            ensureCloudControlChannel(s)
            return
        }
        if (quality.observedDurationMs >= thresholds.cloudPreparationTimeoutMs &&
            preference != TransportPreference.FORCE_LOCAL &&
            !quality.isStableForHandover(thresholds)
        ) {
            startCloudConnect(s)
        }
    }

    private suspend fun handleLocalActive(current: ConnectivityState.LocalActive, s: SessionDescriptor, now: Long) {
        if (preference == TransportPreference.FORCE_CLOUD && canSwitch(now)) {
            beginTransitionToCloud(current.generation, s)
            return
        }
        if (pathEvaluator.isLocalDegraded(thresholds, now) && preference != TransportPreference.FORCE_LOCAL && canSwitch(now)) {
            beginTransitionToCloud(current.generation, s)
            return
        }
        if (!pathEvaluator.current(TransportMode.LOCAL_LAN).reachable && !pathEvaluator.isCloudReachable()) {
            _state.value = ConnectivityState.Reconnecting(TransportMode.LOCAL_LAN, reconnectAttempt++)
        }
    }

    private suspend fun handleConnectingCloud(current: ConnectivityState.ConnectingCloud, s: SessionDescriptor, now: Long) {
        val conn = connectionSwitcher.connectionFor(GenerationId(current.generation))
        if (conn?.isMediaReady == true) {
            val quality = pathEvaluator.current(TransportMode.CLOUD_DIRECT)
            _state.value = ConnectivityState.CloudActive(current.generation, TransportMode.CLOUD_DIRECT, quality)
            activeTransportMode = TransportMode.CLOUD_DIRECT
            recordSwitch("cloud_connected", now)
        } else if (cloudTimedOut(now)) {
            if (preference == TransportPreference.FORCE_CLOUD) {
                _state.value = ConnectivityState.Failed(com.nblaisot.voxcrew.connectivity.model.ConnectivityFailure.CLOUD_UNAVAILABLE)
            } else {
                startLocalConnect(s)
            }
        }
    }

    private suspend fun handleCloudActive(current: ConnectivityState.CloudActive, s: SessionDescriptor, now: Long) {
        if (preference == TransportPreference.FORCE_LOCAL && pathEvaluator.isLocalStable(thresholds) && canSwitch(now)) {
            beginTransitionToLocal(current.generation, s)
            return
        }
        if (preference == TransportPreference.AUTO && pathEvaluator.isLocalStable(thresholds) && canSwitch(now)) {
            beginTransitionToLocal(current.generation, s)
            return
        }
        if (!pathEvaluator.isCloudReachable() && !pathEvaluator.current(TransportMode.LOCAL_LAN).reachable) {
            _state.value = ConnectivityState.Reconnecting(TransportMode.CLOUD_DIRECT, reconnectAttempt++)
        }
    }

    private suspend fun handleTransitioningToLocal(
        current: ConnectivityState.TransitioningToLocal,
        s: SessionDescriptor,
        now: Long,
    ) {
        val candidate = connectionSwitcher.connectionFor(GenerationId(current.candidateGeneration))
        if (candidate?.isMediaReady == true) {
            connectionSwitcher.promote(candidate, "local_stable")
            localGeneration = GenerationId(current.candidateGeneration)
            val quality = pathEvaluator.current(TransportMode.LOCAL_LAN)
            _state.value = ConnectivityState.LocalActive(current.candidateGeneration, quality)
            activeTransportMode = TransportMode.LOCAL_LAN
            recordSwitch("promoted_local", now)
            cloudGeneration?.let { connectionSwitcher.retire(it) }
            return
        }
        if (transitionTimedOut(now)) {
            val cloudConn = connectionSwitcher.connectionFor(GenerationId(current.previousGeneration))
            if (cloudConn != null) {
                _state.value = ConnectivityState.CloudActive(
                    current.previousGeneration,
                    TransportMode.CLOUD_DIRECT,
                    pathEvaluator.current(TransportMode.CLOUD_DIRECT),
                )
                activeTransportMode = TransportMode.CLOUD_DIRECT
            }
        }
    }

    private suspend fun handleTransitioningToCloud(
        current: ConnectivityState.TransitioningToCloud,
        s: SessionDescriptor,
        now: Long,
    ) {
        val candidate = connectionSwitcher.connectionFor(GenerationId(current.candidateGeneration))
        if (candidate?.isMediaReady == true) {
            connectionSwitcher.promote(candidate, "cloud_fallback")
            cloudGeneration = GenerationId(current.candidateGeneration)
            val quality = pathEvaluator.current(TransportMode.CLOUD_DIRECT)
            _state.value = ConnectivityState.CloudActive(current.candidateGeneration, TransportMode.CLOUD_DIRECT, quality)
            activeTransportMode = TransportMode.CLOUD_DIRECT
            recordSwitch("promoted_cloud", now)
            localGeneration?.let { connectionSwitcher.retire(it) }
            return
        }
        if (transitionTimedOut(now) && pathEvaluator.isLocalStable(thresholds)) {
            _state.value = ConnectivityState.LocalActive(
                current.previousGeneration,
                pathEvaluator.current(TransportMode.LOCAL_LAN),
            )
            activeTransportMode = TransportMode.LOCAL_LAN
            candidateGeneration?.let { connectionSwitcher.retire(it) }
        }
    }

    private suspend fun handleReconnecting(now: Long) {
        delay(500)
        _state.value = ConnectivityState.Discovering
    }

    private suspend fun startLocalConnect(s: SessionDescriptor) {
        val gen = GenerationId.next()
        localGeneration = gen
        val peer = localDiscovery.discoveredPeers.value.firstOrNull()
        if (peer != null && localTransport is com.nblaisot.voxcrew.connectivity.transport.LocalLanSignalingTransport) {
            localTransport.configureEndpoint(peer.host, peer.port)
        }
        localTransport.connect(s.copy(sessionSecret = s.sessionSecret ?: localServer?.info?.value?.sessionSecret?.token), gen)
        connectionSwitcher.createConnection(gen, isInitiatorFor(s), useLanIce = true)
        _state.value = ConnectivityState.ConnectingLocal(gen.value)
        activeTransportMode = TransportMode.LOCAL_LAN
    }

    private suspend fun startCloudConnect(s: SessionDescriptor) {
        val gen = GenerationId.next()
        cloudGeneration = gen
        cloudTransport.connect(s, gen)
        connectionSwitcher.createConnection(gen, isInitiatorFor(s), useLanIce = false)
        _state.value = ConnectivityState.ConnectingCloud(gen.value)
    }

    private suspend fun beginTransitionToCloud(previousGen: Long, s: SessionDescriptor) {
        val gen = GenerationId.next()
        candidateGeneration = gen
        cloudTransport.connect(s, gen)
        connectionSwitcher.createConnection(gen, isInitiatorFor(s), useLanIce = false)
        _state.value = ConnectivityState.TransitioningToCloud(previousGen, gen.value)
        transitionStartedAt = clock()
        lastSwitchAtMs = clock()
    }

    private suspend fun beginTransitionToLocal(previousGen: Long, s: SessionDescriptor) {
        val gen = GenerationId.next()
        candidateGeneration = gen
        val peer = localDiscovery.discoveredPeers.value.firstOrNull()
        if (peer != null && localTransport is com.nblaisot.voxcrew.connectivity.transport.LocalLanSignalingTransport) {
            localTransport.configureEndpoint(peer.host, peer.port)
        }
        localTransport.connect(s.copy(sessionSecret = s.sessionSecret ?: localServer?.info?.value?.sessionSecret?.token), gen)
        connectionSwitcher.createConnection(gen, isInitiatorFor(s), useLanIce = true)
        _state.value = ConnectivityState.TransitioningToLocal(previousGen, gen.value)
        transitionStartedAt = clock()
        lastSwitchAtMs = clock()
    }

    private suspend fun ensureCloudControlChannel(s: SessionDescriptor) {
        if (cloudControlConnected || cloudGeneration != null) return
        val gen = GenerationId.next()
        cloudTransport.connect(s, gen)
        cloudControlConnected = cloudTransport.state.value.connectionState == ConnectionState.AUTHENTICATED
    }

    private fun probePaths(now: Long) {
        val localOk = localTransport.state.value.connectionState == ConnectionState.AUTHENTICATED
        val cloudOk = cloudTransport.state.value.connectionState == ConnectionState.AUTHENTICATED
        val localRtt = connectionSwitcher.diagnostics.value.lastDataChannelRttMs
        pathEvaluator.evaluate(TransportMode.LOCAL_LAN, localOk, localRtt, null, now)
        pathEvaluator.evaluate(TransportMode.CLOUD_DIRECT, cloudOk, localRtt, null, now)
    }

    private fun activeTransport(): SignalingTransport? = when (activeTransportMode) {
        TransportMode.LOCAL_LAN -> localTransport
        TransportMode.CLOUD_DIRECT, TransportMode.CLOUD_RELAY -> cloudTransport
        TransportMode.NONE -> null
    }

    private fun isInitiatorFor(s: SessionDescriptor): Boolean {
        val host = s.hostParticipantId ?: s.participantId
        return s.participantId == host || s.participantId <= (s.hostParticipantId ?: s.participantId)
    }

    private fun canSwitch(now: Long): Boolean = now - lastSwitchAtMs >= thresholds.switchCooldownMs

    private fun cloudTimedOut(now: Long): Boolean {
        val connecting = _state.value as? ConnectivityState.ConnectingCloud ?: return false
        return pathEvaluator.current(TransportMode.CLOUD_DIRECT).observedDurationMs >= thresholds.cloudPreparationTimeoutMs
    }

    private var transitionStartedAt = 0L
    private fun transitionTimedOut(now: Long): Boolean =
        transitionStartedAt > 0 && now - transitionStartedAt >= thresholds.cloudPreparationTimeoutMs

    private fun recordSwitch(reason: String, now: Long) {
        lastSwitchAtMs = now
        _diagnostics.update { it.copy(lastSwitchReason = reason, lastSwitchAtMs = now) }
    }

    private fun startEvaluationLoop() {
        if (!enableAutoEvaluation) {
            scope.launch {
                localTransport.incomingMessages.collect { handleIncoming(it, SignalingTransportKind.LOCAL_LAN) }
            }
            scope.launch {
                cloudTransport.incomingMessages.collect { handleIncoming(it, SignalingTransportKind.CLOUD) }
            }
            return
        }
        evaluateJob?.cancel()
        evaluateJob = scope.launch {
            while (isActive) {
                evaluateNow()
                delay(thresholds.localProbeIntervalMs)
            }
        }
        scope.launch {
            localTransport.incomingMessages.collect { handleIncoming(it, SignalingTransportKind.LOCAL_LAN) }
        }
        scope.launch {
            cloudTransport.incomingMessages.collect { handleIncoming(it, SignalingTransportKind.CLOUD) }
        }
    }

    private suspend fun handleIncoming(envelope: SignalingEnvelope, kind: SignalingTransportKind) {
        val gen = envelope.payload["generation"]?.jsonPrimitive?.content?.toLongOrNull()
        val activeGen = connectionSwitcher.activeGeneration.value?.value
        if (gen != null && activeGen != null && gen < activeGen) {
            return // obsolete generation
        }
        if (envelope.type in setOf(
                SignalingMessageTypes.OFFER,
                SignalingMessageTypes.ANSWER,
                SignalingMessageTypes.ICE_CANDIDATE,
            )
        ) {
            _relayed.emit(envelope)
        }
    }

    private fun updateDiagnostics() {
        val localPeer = localDiscovery.discoveredPeers.value.isNotEmpty()
        _diagnostics.update {
            it.copy(
                activeTransport = activeTransportMode,
                connectivityState = _state.value,
                activeGeneration = connectionSwitcher.activeGeneration.value?.value,
                candidateGeneration = candidateGeneration?.value,
                localPeerDiscovered = localPeer,
                localRttMs = connectionSwitcher.diagnostics.value.lastDataChannelRttMs,
                cloudSignalingConnected = cloudTransport.state.value.connectionState == ConnectionState.AUTHENTICATED,
            )
        }
    }
}
