package com.nblaisot.voxcrew.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface AudioPermissionChecker {
    fun hasRecordAudioPermission(): Boolean
}

private class AndroidAudioPermissionChecker(
    private val context: Context,
) : AudioPermissionChecker {
    override fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

/** Jetpack Telecom is the only routing and audio-focus authority for the intercom call. */
class IntercomTelecomSession(
    context: Context,
    private val scope: CoroutineScope,
    private val callsManager: CallsManager = CallsManager(context.applicationContext),
    private val permissionChecker: AudioPermissionChecker =
        AndroidAudioPermissionChecker(context.applicationContext),
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val lifecycleLock = Any()
    private val activationMutex = Mutex()
    private var endpointCatalogJob: Job? = null
    private var sessionJob: Job? = null
    private var stopJob: Job? = null
    private var controllerReady = CompletableDeferred<TelecomCallController?>()
    private val sessionGenerations = TelecomSessionGenerationArbiter()
    private var activeCoordinator: TelecomRouteCoordinator? = null
    private var activeCoordinatorGeneration: Long? = null
    @Volatile private var latestStartingEndpoints: List<CallEndpointCompat> = emptyList()
    private var onMediaInactive: suspend () -> Unit = { }
    private var onMediaActive: suspend () -> Unit = { }
    private var onMediaDisconnected: suspend (preserveTransmission: Boolean) -> Unit = { }

    private val _callState = MutableStateFlow(TelecomCallState())
    val callState: StateFlow<TelecomCallState> = _callState.asStateFlow()

    private val _routeSelection = MutableStateFlow(AudioRouteSelectionState())
    val routeSelection: StateFlow<AudioRouteSelectionState> = _routeSelection.asStateFlow()

    val currentState: TelecomCallState get() = _callState.value
    val isActive: Boolean get() = currentState.phase == TelecomCallPhase.ACTIVE
    val hasCall: Boolean
        get() = synchronized(lifecycleLock) {
            sessionJob?.isActive == true || stopJob?.isActive == true
        }

    init {
        runCatching { callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
            .onFailure { error ->
                publish(
                    TelecomCallState(
                        phase = TelecomCallPhase.FAILED,
                        sessionIssue = AudioSessionIssue.TELECOM_UNAVAILABLE,
                    ),
                    "Telecom registration failed: ${error.message}",
                )
            }
        refreshEndpointCatalog()
    }

    fun setMediaLifecycleCallbacks(
        onInactive: suspend () -> Unit,
        onActive: suspend () -> Unit,
        onDisconnected: suspend (preserveTransmission: Boolean) -> Unit,
    ) {
        onMediaInactive = onInactive
        onMediaActive = onActive
        onMediaDisconnected = onDisconnected
    }

    fun refreshEndpointCatalog() {
        endpointCatalogJob?.cancel()
        endpointCatalogJob = scope.launch(Dispatchers.IO) {
            runCatching {
                callsManager.getAvailableStartingCallEndpoints().collect { endpoints ->
                    updateStartingEndpoints(endpoints)
                }
            }.onFailure { error ->
                if (error !is CancellationException) {
                    Log.w(TAG, "Telecom endpoint catalog unavailable: ${error.message}")
                }
            }
        }
    }

    fun selectAudioRoute(key: String) {
        scope.launch(Dispatchers.IO) {
            val choice = _routeSelection.value.availableChoices.firstOrNull { it.key == key }
                ?: return@launch
            _routeSelection.value = _routeSelection.value.copy(selectedChoice = choice)
            currentCoordinator()?.onSelectedEndpoint(resolveSelectedEndpoint(latestStartingEndpoints))
        }
    }

    fun retrySelectedRoute() {
        scope.launch(Dispatchers.IO) {
            currentCoordinator()?.retrySelectedEndpoint()
        }
    }

    fun start() {
        startIfAllowed()
    }

    private fun startIfAllowed(): CompletableDeferred<TelecomCallController?>? =
        synchronized(lifecycleLock) {
            if (stopJob?.isActive == true) return@synchronized null
            if (sessionGenerations.hasActiveGeneration() && sessionJob?.isActive == true) {
                return@synchronized controllerReady
            }
            startLocked()
        }

    /** lifecycleLock must be held. The lazy job is published before it can execute. */
    private fun startLocked(): CompletableDeferred<TelecomCallController?>? {
        if (!permissionChecker.hasRecordAudioPermission()) {
            publish(
                TelecomCallState(
                    phase = TelecomCallPhase.FAILED,
                    sessionIssue = AudioSessionIssue.AUDIO_PIPELINE_FAILED,
                ),
                "RECORD_AUDIO permission missing",
            )
            return null
        }

        val lease = sessionGenerations.acquire()
        check(lease.isNew) { "Telecom generation already active without a session job" }
        val generation = lease.generation
        val ready = CompletableDeferred<TelecomCallController?>()
        controllerReady = ready
        publish(TelecomCallState(phase = TelecomCallPhase.STARTING), "Telecom session starting")
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val startingEndpoints = latestStartingEndpoints.takeIf { it.isNotEmpty() }
                    ?: callsManager.getAvailableStartingCallEndpoints().first()
                updateStartingEndpoints(startingEndpoints)
                val preferred = resolveSelectedFrameworkEndpoint(startingEndpoints)
                    ?: error("Selected Telecom endpoint is unavailable")
                val selected = preferred.toTelecomEndpoint()
                publishCurrent(
                    generation,
                    currentState.copy(
                        phase = TelecomCallPhase.STARTING,
                        selectedEndpoint = selected,
                        availableEndpoints = startingEndpoints.map { it.toTelecomEndpoint() },
                    ),
                    "starting endpoint=${preferred.name}",
                )
                val attributes = CallAttributesCompat(
                    displayName = DISPLAY_NAME,
                    address = Uri.fromParts(SCHEME, HOST, null),
                    direction = CallAttributesCompat.DIRECTION_OUTGOING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    callCapabilities = 0,
                    preferredStartingCallEndpoint = preferred,
                )
                var routeCoordinator: TelecomRouteCoordinator? = null
                callsManager.addCall(
                    callAttributes = attributes,
                    onAnswer = { },
                    onDisconnect = {
                        if (isCurrentGeneration(generation)) {
                            onMediaDisconnected(false)
                            routeCoordinator?.onDisconnected()
                            currentSessionJob(generation)?.cancel()
                        }
                    },
                    onSetActive = {
                        if (isCurrentGeneration(generation)) {
                            routeCoordinator?.onActive()
                            onMediaActive()
                        }
                    },
                    onSetInactive = {
                        if (isCurrentGeneration(generation)) {
                            onMediaInactive()
                            routeCoordinator?.onInactive()
                            scope.launch(Dispatchers.IO) { disconnect() }
                        }
                    },
                ) {
                    runBlocking(coroutineContext) {
                        var frameworkEndpoints: List<CallEndpointCompat> = emptyList()
                        val coordinator = TelecomRouteCoordinator(
                            requestEndpoint = request@{ endpoint ->
                                if (!isCurrentGeneration(generation)) return@request false
                                val frameworkEndpoint = frameworkEndpoints
                                    .firstOrNull { it.identifier.toString() == endpoint.identifier }
                                    ?: return@request false
                                requestEndpointChange(frameworkEndpoint) is CallControlResult.Success
                            },
                            publishState = { state, reason ->
                                publishCurrent(generation, state, reason)
                            },
                            selectedEndpoint = selected,
                        )
                        routeCoordinator = coordinator
                        setActiveCoordinator(generation, coordinator)
                        val controller = object : TelecomCallController {
                            override suspend fun activate(): Boolean {
                                if (!isCurrentGeneration(generation)) return false
                                val success = setActive() is CallControlResult.Success
                                if (!isCurrentGeneration(generation)) return false
                                coordinator.onActivationResult(success)
                                if (success) onMediaActive()
                                return success
                            }
                        }
                        ready.complete(controller)
                        val currentJob = launch {
                            currentCallEndpoint.collect {
                                coordinator.onCurrentEndpoint(it.toTelecomEndpoint())
                            }
                        }
                        val availableJob = launch {
                            availableEndpoints.collect { endpoints ->
                                frameworkEndpoints = endpoints
                                coordinator.onAvailableEndpoints(endpoints.map { it.toTelecomEndpoint() })
                            }
                        }
                        coordinator.onCallReady()
                        try {
                            awaitCancellation()
                        } finally {
                            currentJob.cancel()
                            availableJob.cancel()
                            clearActiveCoordinator(generation, coordinator)
                            withContext(NonCancellable) {
                                runCatching { disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!ready.isCompleted) ready.complete(null)
                if (isCurrentGeneration(generation)) {
                    publishCurrent(
                        generation,
                        currentState.copy(
                            phase = TelecomCallPhase.FAILED,
                            sessionIssue = AudioSessionIssue.TELECOM_UNAVAILABLE,
                        ),
                        "Telecom intercom session failed: ${error.message}",
                    )
                    onMediaDisconnected(false)
                }
            } finally {
                if (!ready.isCompleted) ready.complete(null)
                clearActiveCoordinator(generation)
                finishGeneration(generation)
            }
        }
        sessionJob = job
        job.start()
        return ready
    }

    fun refresh() {
        startIfAllowed()
    }

    suspend fun activate(): Boolean = activationMutex.withLock {
        while (true) {
            currentStopJob()?.join()
            if (isActive) return@withLock true
            val ready = startIfAllowed()
            if (ready == null) {
                val cleanup = currentStopJob() ?: return@withLock false
                cleanup.join()
                continue
            }
            val controller = ready.await() ?: return@withLock false
            if (!isCurrentController(ready)) {
                if (currentStopJob() != null) continue
                return@withLock false
            }
            return@withLock controller.activate()
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    suspend fun disconnect() {
        stop()
        currentStopJob()?.join()
    }

    fun stop() {
        val cleanup = synchronized(lifecycleLock) {
            if (!sessionGenerations.hasActiveGeneration()) return
            sessionGenerations.invalidate()
            val job = sessionJob
            sessionJob = null
            activeCoordinator = null
            activeCoordinatorGeneration = null
            controllerReady = completedNullController()
            publish(TelecomCallState(phase = TelecomCallPhase.STOPPED), "Telecom session stopped")
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                onMediaDisconnected(true)
                job?.cancelAndJoin()
            }.also { stopJob = it }
        }
        cleanup.invokeOnCompletion {
            synchronized(lifecycleLock) {
                if (stopJob === cleanup) stopJob = null
            }
        }
        cleanup.start()
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lifecycleLock) { sessionGenerations.isCurrent(generation) }

    private fun isCurrentController(
        ready: CompletableDeferred<TelecomCallController?>,
    ): Boolean = synchronized(lifecycleLock) {
        sessionGenerations.hasActiveGeneration() &&
            controllerReady === ready &&
            sessionJob?.isActive == true
    }

    private fun currentSessionJob(generation: Long): Job? =
        synchronized(lifecycleLock) { sessionJob.takeIf { sessionGenerations.isCurrent(generation) } }

    private fun currentStopJob(): Job? = synchronized(lifecycleLock) { stopJob }

    private fun currentCoordinator(): TelecomRouteCoordinator? = synchronized(lifecycleLock) {
        activeCoordinator.takeIf {
            activeCoordinatorGeneration != null &&
                activeCoordinatorGeneration == sessionGenerations.currentGeneration()
        }
    }

    private fun setActiveCoordinator(
        generation: Long,
        coordinator: TelecomRouteCoordinator,
    ) {
        synchronized(lifecycleLock) {
            if (sessionGenerations.isCurrent(generation)) {
                activeCoordinator = coordinator
                activeCoordinatorGeneration = generation
            }
        }
    }

    private fun clearActiveCoordinator(
        generation: Long,
        coordinator: TelecomRouteCoordinator? = null,
    ) {
        synchronized(lifecycleLock) {
            if (activeCoordinatorGeneration == generation &&
                (coordinator == null || activeCoordinator === coordinator)
            ) {
                activeCoordinator = null
                activeCoordinatorGeneration = null
            }
        }
    }

    private fun finishGeneration(generation: Long) {
        synchronized(lifecycleLock) {
            if (!sessionGenerations.isCurrent(generation)) return
            sessionGenerations.finish(generation)
            sessionJob = null
            controllerReady = completedNullController()
        }
    }

    private fun publishCurrent(
        generation: Long,
        state: TelecomCallState,
        reason: String,
    ) {
        synchronized(lifecycleLock) {
            if (!sessionGenerations.isCurrent(generation)) {
                Log.i(TAG, "Ignoring stale Telecom callback generation=$generation reason=$reason")
                return
            }
            publish(state, reason)
        }
    }

    private suspend fun updateStartingEndpoints(endpoints: List<CallEndpointCompat>) {
        latestStartingEndpoints = endpoints
        val choices = buildAudioRouteChoices(
            endpoints = endpoints.map { it.toTelecomEndpoint() },
            usbProductNames = connectedUsbProductNames(),
        )
        val previousKey = _routeSelection.value.selectedChoice.key
        val selected = selectedAudioRouteChoice(choices, previousKey)
        _routeSelection.value = AudioRouteSelectionState(choices, selected)
        currentCoordinator()?.onSelectedEndpoint(resolveSelectedEndpoint(endpoints))
    }

    private fun resolveSelectedFrameworkEndpoint(
        endpoints: List<CallEndpointCompat>,
    ): CallEndpointCompat? {
        val choice = _routeSelection.value.selectedChoice
        return if (choice.key == DEVICE_AUDIO_ROUTE_KEY) {
            endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        } else {
            endpoints.firstOrNull { it.identifier.toString() == choice.endpointIdentifier }
        }
    }

    private fun resolveSelectedEndpoint(endpoints: List<CallEndpointCompat>): TelecomEndpoint? =
        resolveSelectedFrameworkEndpoint(endpoints)?.toTelecomEndpoint()

    private fun connectedUsbProductNames(): Set<String> = audioManager
        .getDevices(AudioManager.GET_DEVICES_ALL)
        .asSequence()
        .filter { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        .map { it.productName.toString().normalizeAudioDeviceName() }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun publish(state: TelecomCallState, reason: String) {
        if (_callState.value == state) return
        _callState.value = state
        Log.i(
            TAG,
            "$reason phase=${state.phase} endpoint=${state.currentEndpoint?.name} " +
                "type=${state.currentEndpoint?.type} id=${state.currentEndpoint?.identifier} " +
                "selected=${state.selectedEndpoint?.name} available=${state.availableEndpoints.map { it.type }} " +
                "issue=${state.sessionIssue} warning=${state.routeRequestWarning}",
        )
    }

    companion object {
        private const val TAG = "IntercomTelecomSession"
        private const val DISPLAY_NAME = "VoxCrew"
        private const val SCHEME = "sip"
        private const val HOST = "intercom@voxcrew"
    }
}

private fun completedNullController() =
    CompletableDeferred<TelecomCallController?>().also { it.complete(null) }

/**
 * Atomic ownership token for a Telecom call. Every callback carries its generation, so
 * callbacks from a stopped call cannot mutate the replacement session.
 */
internal class TelecomSessionGenerationArbiter {
    data class Lease(val generation: Long, val isNew: Boolean)

    private var nextGeneration = 0L
    private var activeGeneration: Long? = null

    @Synchronized
    fun acquire(): Lease {
        activeGeneration?.let { return Lease(it, false) }
        val generation = ++nextGeneration
        activeGeneration = generation
        return Lease(generation, true)
    }

    @Synchronized
    fun invalidate(): Long? = activeGeneration.also { activeGeneration = null }

    @Synchronized
    fun finish(generation: Long): Boolean {
        if (activeGeneration != generation) return false
        activeGeneration = null
        return true
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean = activeGeneration == generation

    @Synchronized
    fun hasActiveGeneration(): Boolean = activeGeneration != null

    @Synchronized
    fun currentGeneration(): Long? = activeGeneration
}

internal fun buildAudioRouteChoices(
    endpoints: List<TelecomEndpoint>,
    usbProductNames: Set<String> = emptySet(),
): List<AudioRouteChoice> {
    val speaker = endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
    val wiredEndpoints = endpoints.filter { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
    val accessories = endpoints.mapNotNull { endpoint ->
        val kind = when (endpoint.type) {
            CallEndpointCompat.TYPE_BLUETOOTH -> CaptureInputKind.BLUETOOTH
            CallEndpointCompat.TYPE_WIRED_HEADSET -> {
                val normalizedName = endpoint.name.normalizeAudioDeviceName()
                val isUsb = normalizedName in usbProductNames ||
                    (wiredEndpoints.size == 1 && usbProductNames.isNotEmpty())
                if (isUsb) CaptureInputKind.USB else CaptureInputKind.WIRED
            }
            else -> return@mapNotNull null
        }
        AudioRouteChoice(
            key = "endpoint:${endpoint.identifier}",
            name = endpoint.name,
            inputKind = kind,
            endpointIdentifier = endpoint.identifier,
            endpointType = endpoint.type,
        )
    }.sortedWith(compareBy<AudioRouteChoice>({ it.inputKind.routeOrder() }, { it.name.lowercase() }))
    return listOf(deviceAudioRouteChoice(speaker?.identifier)) + accessories
}

internal fun selectedAudioRouteChoice(
    availableChoices: List<AudioRouteChoice>,
    previousKey: String,
): AudioRouteChoice = availableChoices.firstOrNull { it.key == previousKey }
    ?: availableChoices.first { it.key == DEVICE_AUDIO_ROUTE_KEY }

private fun CaptureInputKind.routeOrder(): Int = when (this) {
    CaptureInputKind.BLUETOOTH -> 0
    CaptureInputKind.USB -> 1
    CaptureInputKind.WIRED -> 2
    CaptureInputKind.BUILTIN -> 3
}

private fun String.normalizeAudioDeviceName(): String = trim().lowercase()

/** Pure, timer-free policy that enforces the exact endpoint selected by the user. */
internal class TelecomRouteCoordinator(
    private val requestEndpoint: suspend (TelecomEndpoint) -> Boolean,
    private val publishState: (TelecomCallState, String) -> Unit,
    selectedEndpoint: TelecomEndpoint,
) {
    private var phase = TelecomCallPhase.STARTING
    private var current: TelecomEndpoint? = null
    private var selected: TelecomEndpoint? = selectedEndpoint
    private var available: List<TelecomEndpoint> = emptyList()
    private var requestedEndpointId: String? = null
    private var warning: RouteRequestWarning? = null

    suspend fun onActivationResult(success: Boolean) {
        phase = if (success) TelecomCallPhase.ACTIVE else TelecomCallPhase.FAILED
        if (!success) {
            publish(
                sessionIssue = AudioSessionIssue.TELECOM_UNAVAILABLE,
                reason = "Telecom call activation failed",
            )
            return
        }
        publish(reason = "Telecom call active")
        enforceSelection()
    }

    fun onCallReady() {
        phase = TelecomCallPhase.STARTING
        publish(reason = "Telecom call ready")
    }

    suspend fun onAvailableEndpoints(endpoints: List<TelecomEndpoint>) {
        available = endpoints
        selected = selected?.let { desired ->
            endpoints.firstOrNull { it.identifier == desired.identifier } ?: desired
        }
        publish(reason = "Telecom endpoints changed")
        enforceSelection()
    }

    suspend fun onSelectedEndpoint(endpoint: TelecomEndpoint?) {
        if (selected?.identifier == endpoint?.identifier) return
        selected = endpoint
        requestedEndpointId = null
        warning = null
        publish(reason = "User audio endpoint selected")
        enforceSelection()
    }

    suspend fun retrySelectedEndpoint() {
        requestedEndpointId = null
        warning = null
        publish(reason = "User retried selected audio endpoint")
        enforceSelection()
    }

    suspend fun onCurrentEndpoint(endpoint: TelecomEndpoint) {
        current = endpoint
        if (endpoint.identifier == selected?.identifier) {
            requestedEndpointId = null
            warning = null
        }
        publish(reason = "Telecom current endpoint confirmed")
        enforceSelection()
    }

    fun onInactive() {
        phase = TelecomCallPhase.INACTIVE
        publish(reason = "Telecom requested inactive; disconnecting")
    }

    suspend fun onActive() {
        phase = TelecomCallPhase.ACTIVE
        publish(reason = "Telecom call active")
        enforceSelection()
    }

    fun onDisconnected() {
        phase = TelecomCallPhase.STOPPED
        publish(reason = "Telecom call disconnected")
    }

    private suspend fun enforceSelection() {
        if (phase != TelecomCallPhase.ACTIVE) return
        val desired = selected ?: return
        if (current?.identifier == desired.identifier) return
        val target = available.firstOrNull { it.identifier == desired.identifier } ?: return
        if (requestedEndpointId == target.identifier) return
        requestedEndpointId = target.identifier
        if (!requestEndpoint(target)) {
            warning = RouteRequestWarning.ENDPOINT_CHANGE_FAILED
            publish(reason = "selected endpoint request failed; media remains closed")
        } else {
            publish(reason = "selected endpoint requested; waiting for confirmation")
        }
    }

    private fun publish(
        sessionIssue: AudioSessionIssue? = null,
        reason: String,
    ) {
        publishState(
            TelecomCallState(
                phase = phase,
                currentEndpoint = current,
                selectedEndpoint = selected,
                availableEndpoints = available,
                sessionIssue = sessionIssue,
                routeRequestWarning = warning,
            ),
            reason,
        )
    }
}

private fun CallEndpointCompat.toTelecomEndpoint(): TelecomEndpoint = TelecomEndpoint(
    identifier = identifier.toString(),
    name = name.toString(),
    type = type,
)

private interface TelecomCallController {
    suspend fun activate(): Boolean
}
