package com.nblaisot.voxcrew.connectivity.webrtc

import com.nblaisot.voxcrew.audio.TransmissionPolicy
import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.webrtc.IceServerConfig
import com.nblaisot.voxcrew.webrtc.WebRtcDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WebRtcConnectionSwitcherImpl(
    private val factoryFacade: PeerConnectionFactoryFacade,
    private val cloudIce: IceServerConfig,
    private val lanIce: IceServerConfig = cloudIce,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : WebRtcConnectionSwitcher {
    private val connections = mutableMapOf<Long, ManagedPeerConnectionImpl>()
    private var active: ManagedPeerConnectionImpl? = null
    private var policyJob: kotlinx.coroutines.Job? = null

    private val _activeGeneration = MutableStateFlow<GenerationId?>(null)
    override val activeGeneration: StateFlow<GenerationId?> = _activeGeneration.asStateFlow()

    private val _diagnostics = MutableStateFlow(WebRtcDiagnostics())
    override val diagnostics: StateFlow<WebRtcDiagnostics> = _diagnostics.asStateFlow()

    private val _lastSwitch = MutableStateFlow<SwitchEvent?>(null)
    override val lastSwitch: StateFlow<SwitchEvent?> = _lastSwitch.asStateFlow()

    private val promoteMutex = Mutex()

    override fun createConnection(
        generation: GenerationId,
        isInitiator: Boolean,
        useLanIce: Boolean,
    ): ManagedPeerConnection {
        val conn = ManagedPeerConnectionImpl(
            generation = generation,
            factoryFacade = factoryFacade,
            cloudIce = cloudIce,
            lanIce = lanIce,
            useLanIce = useLanIce,
            scope = scope,
        )
        conn.createDataChannelIfInitiator(isInitiator)
        connections[generation.value] = conn
        if (active == null) {
            active = conn
            _activeGeneration.value = generation
            bindDiagnostics(conn)
        }
        return conn
    }

    override suspend fun promote(candidate: ManagedPeerConnection, reason: String) {
        promoteMutex.withLock {
            val impl = candidate as? ManagedPeerConnectionImpl ?: return
            val previous = active
            if (previous?.generation == impl.generation) return
            previous?.muteIncomingAudio(true)
            impl.muteIncomingAudio(false)
            active = impl
            _activeGeneration.value = impl.generation
            bindDiagnostics(impl)
            _lastSwitch.value = SwitchEvent(
                fromGeneration = previous?.generation,
                toGeneration = impl.generation,
                reason = reason,
                atMs = System.currentTimeMillis(),
            )
            previous?.let { retire(it.generation) }
        }
    }

    override suspend fun retire(generation: GenerationId) {
        val conn = connections.remove(generation.value) ?: return
        if (active?.generation == generation) {
            active = null
            _activeGeneration.value = null
        }
        conn.close()
    }

    override fun activeConnection(): ManagedPeerConnection? = active

    override fun connectionFor(generation: GenerationId): ManagedPeerConnection? =
        connections[generation.value]

    override fun attachTransmissionPolicy(policy: TransmissionPolicy) {
        policyJob?.cancel()
        policyJob = policy.shouldTransmit
            .onEach { transmit ->
                active?.ensureAudioTrack(transmit)
                active?.setLocalAudioEnabled(transmit)
            }
            .launchIn(scope)
    }

    override fun closeAll() {
        policyJob?.cancel()
        connections.values.toList().forEach { it.close() }
        connections.clear()
        active = null
        _activeGeneration.value = null
        _diagnostics.value = WebRtcDiagnostics()
    }

    private fun bindDiagnostics(conn: ManagedPeerConnectionImpl) {
        scope.launch {
            conn.diagnostics.collect { diag ->
                if (active?.generation == conn.generation) {
                    _diagnostics.value = diag
                }
            }
        }
    }
}
