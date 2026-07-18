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
import com.nblaisot.voxcrew.demo.DemoFixtures
import com.nblaisot.voxcrew.demo.DemoModeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

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
    private val demoModeStore: DemoModeStore? = null,
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
    private var activeCallDisconnect: (suspend () -> Unit)? = null
    private val routeActivationGate = ManualRouteActivationGate()
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
    val isRouteSelectionBlocked: Boolean get() = routeActivationGate.isBlocked
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
        if (demoModeStore != null) {
            scope.launch {
                demoModeStore.enabled.collect { rebuildChoicesFromLatestEndpoints() }
            }
        }
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

    suspend fun selectAudioRoute(key: String) {
        val choice = _routeSelection.value.availableChoices.firstOrNull { it.key == key }
            ?: return
        if (DemoFixtures.isDemoAudioRouteKey(key)) {
            _routeSelection.value = _routeSelection.value.copy(
                selectedChoice = choice,
                status = ManualRouteStatus.CONFIRMED,
                confirmedChoiceKey = choice.key,
                errorCode = null,
            )
            return
        }
        routeActivationGate.onUserSelection()
        _routeSelection.value = _routeSelection.value.copy(
            selectedChoice = choice,
            status = ManualRouteStatus.STARTING,
            confirmedChoiceKey = null,
            errorCode = null,
        )
        val coordinator = currentCoordinator()
        if (coordinator == null) return
        when (coordinator.onUserSelected(choice)) {
            ManualRouteCommandResult.Failed -> {
                routeActivationGate.onRouteFailure()
                disconnect()
            }
            ManualRouteCommandResult.Unavailable -> routeActivationGate.onRouteFailure()
            ManualRouteCommandResult.Busy,
            ManualRouteCommandResult.Accepted -> Unit
        }
    }

    fun start() {
        startIfAllowed()
    }

    private fun startIfAllowed(): CompletableDeferred<TelecomCallController?>? =
        synchronized(lifecycleLock) {
            if (routeActivationGate.isBlocked) return@synchronized null
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
        updateRouteSelectionStatus(ManualRouteStatus.STARTING)
        publish(TelecomCallState(phase = TelecomCallPhase.STARTING), "Telecom session starting")
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val startingEndpoints = latestStartingEndpoints.takeIf { it.isNotEmpty() }
                    ?: callsManager.getAvailableStartingCallEndpoints().first()
                updateStartingEndpoints(startingEndpoints)
                val preferred = resolveSelectedFrameworkEndpoint(startingEndpoints)
                if (preferred == null) {
                    routeActivationGate.onRouteFailure()
                    updateRouteSelectionStatus(ManualRouteStatus.UNAVAILABLE)
                    publishCurrent(
                        generation,
                        TelecomCallState(phase = TelecomCallPhase.STOPPED),
                        "Selected Telecom endpoint is unavailable",
                    )
                    ready.complete(null)
                    return@launch
                }
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
                    val frameworkEndpoints = AtomicReference<List<CallEndpointCompat>>(emptyList())
                    val coordinator = TelecomRouteCoordinator(
                        requestEndpoint = request@{ endpoint ->
                            if (!isCurrentGeneration(generation)) {
                                return@request EndpointRequestResult(errorCode = null)
                            }
                            val frameworkEndpoint = frameworkEndpoints.get()
                                .firstOrNull { it.identifier.toString() == endpoint.identifier }
                                ?: return@request EndpointRequestResult(errorCode = null)
                            val result = requestEndpointChange(frameworkEndpoint)
                            if (result is CallControlResult.Success) {
                                EndpointRequestResult(success = true)
                            } else {
                                val errorCode = (result as? CallControlResult.Error)?.errorCode
                                Log.w(
                                    TAG,
                                    "manual endpoint request rejected type=${endpoint.type} " +
                                        "errorCode=$errorCode result=$result",
                                )
                                EndpointRequestResult(errorCode = errorCode)
                            }
                        },
                        publishState = { state, reason ->
                            publishCurrent(generation, state, reason)
                        },
                        publishRouteStatus = { status, errorCode ->
                            if (isCurrentGeneration(generation)) {
                                if (status == ManualRouteStatus.FAILED ||
                                    status == ManualRouteStatus.UNAVAILABLE
                                ) {
                                    routeActivationGate.onRouteFailure()
                                }
                                updateRouteSelectionStatus(status, errorCode)
                            } else {
                                Log.i(
                                    TAG,
                                    "Ignoring stale route status generation=$generation status=$status",
                                )
                            }
                        },
                        selectedEndpoint = selected,
                    )
                    routeCoordinator = coordinator
                    setActiveCoordinator(generation, coordinator)
                    setActiveCallDisconnect(generation) {
                        disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    }
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
                    launch {
                        currentCallEndpoint.collect {
                            coordinator.onCurrentEndpoint(it.toTelecomEndpoint())
                        }
                    }
                    launch {
                        availableEndpoints.collect { endpoints ->
                            frameworkEndpoints.set(endpoints)
                            coordinator.onAvailableEndpoints(endpoints.map { it.toTelecomEndpoint() })
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
                clearActiveCallDisconnect(generation)
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
            val disconnectCall = activeCallDisconnect
            sessionJob = null
            activeCoordinator = null
            activeCoordinatorGeneration = null
            activeCallDisconnect = null
            controllerReady = completedNullController()
            if (!routeActivationGate.isBlocked) {
                updateRouteSelectionStatus(ManualRouteStatus.STARTING)
            }
            publish(TelecomCallState(phase = TelecomCallPhase.STOPPED), "Telecom session stopped")
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                onMediaDisconnected(true)
                withContext(NonCancellable) {
                    runCatching { disconnectCall?.invoke() }
                        .onFailure { Log.w(TAG, "Telecom disconnect failed: ${it.message}") }
                }
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

    private fun setActiveCallDisconnect(
        generation: Long,
        disconnectCall: suspend () -> Unit,
    ) {
        synchronized(lifecycleLock) {
            if (sessionGenerations.isCurrent(generation)) activeCallDisconnect = disconnectCall
        }
    }

    private fun clearActiveCallDisconnect(generation: Long) {
        synchronized(lifecycleLock) {
            if (activeCoordinatorGeneration == generation ||
                sessionGenerations.isCurrent(generation)
            ) {
                activeCallDisconnect = null
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
        rebuildChoicesFromLatestEndpoints()
        val demoRouteSelected = DemoFixtures.isDemoAudioRouteKey(
            _routeSelection.value.selectedChoice.key,
        )
        // Demo Bluetooth endpoints are UI fixtures — never mark them unavailable.
        if (demoRouteSelected) return
        if (endpoints.isNotEmpty() &&
            currentCoordinator() == null &&
            resolveSelectedFrameworkEndpoint(endpoints) == null
        ) {
            routeActivationGate.onRouteFailure()
            updateRouteSelectionStatus(ManualRouteStatus.UNAVAILABLE)
        }
    }

    private fun rebuildChoicesFromLatestEndpoints() {
        val catalog = latestStartingEndpoints.map { it.toTelecomEndpoint() }
        val demoOn = demoModeStore?.enabled?.value == true
        val withDemo = if (demoOn) {
            catalog + DemoFixtures.bluetoothEndpoints()
        } else {
            catalog
        }
        val choices = buildAudioRouteChoices(
            endpoints = withDemo,
            usbProductNames = connectedUsbProductNames(),
        )
        val previous = _routeSelection.value
        val preferredDemo = if (demoOn) {
            choices.firstOrNull { it.key == DemoFixtures.audioRouteKey(DemoFixtures.EARBUDS_ID) }
        } else {
            null
        }
        val selected = preferredDemo
            ?: selectedAudioRouteChoice(choices, previous.selectedChoice)
        _routeSelection.value = previous.copy(
            availableChoices = choices,
            selectedChoice = selected,
            status = if (preferredDemo != null && DemoFixtures.isDemoAudioRouteKey(selected.key)) {
                ManualRouteStatus.CONFIRMED
            } else {
                previous.status
            },
            confirmedChoiceKey = if (preferredDemo != null) selected.key else previous.confirmedChoiceKey,
        )
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
                "issue=${state.sessionIssue} manualStatus=${_routeSelection.value.status}",
        )
    }

    private fun updateRouteSelectionStatus(
        status: ManualRouteStatus,
        errorCode: Int? = null,
    ) {
        val current = _routeSelection.value
        _routeSelection.value = current.copy(
            status = status,
            confirmedChoiceKey = current.selectedChoice.key.takeIf {
                status == ManualRouteStatus.CONFIRMED
            },
            errorCode = errorCode,
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

/** Prevents media demand from recreating a failed route before another explicit menu choice. */
internal class ManualRouteActivationGate {
    @Volatile
    private var blocked = false

    val isBlocked: Boolean get() = blocked

    fun onRouteFailure() {
        blocked = true
    }

    fun onUserSelection() {
        blocked = false
    }
}

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
            target = if (endpoint.type == CallEndpointCompat.TYPE_BLUETOOTH) {
                AudioRouteTarget.BLUETOOTH
            } else {
                AudioRouteTarget.WIRED_USB
            },
            endpointIdentifier = endpoint.identifier,
            endpointType = endpoint.type,
        )
    }.sortedWith(compareBy<AudioRouteChoice>({ it.inputKind.routeOrder() }, { it.name.lowercase() }))
    return listOf(deviceAudioRouteChoice(speaker?.identifier)) + accessories
}

internal fun selectedAudioRouteChoice(
    availableChoices: List<AudioRouteChoice>,
    previous: AudioRouteChoice,
): AudioRouteChoice = availableChoices.firstOrNull { it.key == previous.key } ?: previous

private fun CaptureInputKind.routeOrder(): Int = when (this) {
    CaptureInputKind.BLUETOOTH -> 0
    CaptureInputKind.USB -> 1
    CaptureInputKind.WIRED -> 2
    CaptureInputKind.BUILTIN -> 3
}

private fun String.normalizeAudioDeviceName(): String = trim().lowercase()

internal data class EndpointRequestResult(
    val success: Boolean = false,
    val errorCode: Int? = null,
)

internal enum class ManualRouteCommandResult {
    Accepted,
    Busy,
    Unavailable,
    Failed,
}

/** Pure, timer-free policy where only an explicit user command can request a route. */
internal class TelecomRouteCoordinator(
    private val requestEndpoint: suspend (TelecomEndpoint) -> EndpointRequestResult,
    private val publishState: (TelecomCallState, String) -> Unit,
    private val publishRouteStatus: (ManualRouteStatus, Int?) -> Unit,
    selectedEndpoint: TelecomEndpoint,
) {
    private val stateMutex = Mutex()
    private var phase = TelecomCallPhase.STARTING
    private var current: TelecomEndpoint? = null
    private var selected: TelecomEndpoint? = selectedEndpoint
    private var available: List<TelecomEndpoint> = emptyList()
    private var status = ManualRouteStatus.STARTING
    private var errorCode: Int? = null
    private var requiresExplicitSelection = false

    suspend fun onActivationResult(success: Boolean) = stateMutex.withLock {
        phase = if (success) TelecomCallPhase.ACTIVE else TelecomCallPhase.FAILED
        if (success) {
            updateStatusFromPlatform()
            publish(reason = "Telecom call active")
        } else {
            status = ManualRouteStatus.FAILED
            publish(
                sessionIssue = AudioSessionIssue.TELECOM_UNAVAILABLE,
                reason = "Telecom call activation failed",
            )
        }
    }

    suspend fun onCallReady() = stateMutex.withLock {
        phase = TelecomCallPhase.STARTING
        status = ManualRouteStatus.STARTING
        publish(reason = "Telecom call ready")
    }

    suspend fun onAvailableEndpoints(endpoints: List<TelecomEndpoint>) = stateMutex.withLock {
        available = endpoints
        selected = selected?.let { desired ->
            if (desired.type == CallEndpointCompat.TYPE_SPEAKER) {
                endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER } ?: desired
            } else {
                endpoints.firstOrNull { it.identifier == desired.identifier } ?: desired
            }
        }
        val desiredAvailable = selected?.let(::findAvailableEndpoint) != null
        if (endpoints.isNotEmpty() && !desiredAvailable) {
            requiresExplicitSelection = true
            status = ManualRouteStatus.UNAVAILABLE
            errorCode = null
        } else if (!requiresExplicitSelection) {
            updateStatusFromPlatform()
        }
        publish(reason = "Telecom endpoints changed")
    }

    suspend fun onUserSelected(choice: AudioRouteChoice): ManualRouteCommandResult {
        if (!stateMutex.tryLock()) return ManualRouteCommandResult.Busy
        try {
            if (phase != TelecomCallPhase.ACTIVE) return ManualRouteCommandResult.Busy
            val target = resolveChoice(choice)
            if (target == null) {
                selected = choice.toTelecomEndpointPlaceholder()
                requiresExplicitSelection = true
                status = ManualRouteStatus.UNAVAILABLE
                errorCode = null
                publish(reason = "User-selected endpoint is unavailable")
                return ManualRouteCommandResult.Unavailable
            }

            selected = target
            requiresExplicitSelection = false
            errorCode = null
            if (current?.identifier == target.identifier) {
                status = ManualRouteStatus.CONFIRMED
                publish(reason = "User confirmed current audio endpoint")
                return ManualRouteCommandResult.Accepted
            }

            status = ManualRouteStatus.REQUESTING
            publish(reason = "User requested audio endpoint")
            val result = requestEndpoint(target)
            if (result.success) {
                publish(reason = "User endpoint request accepted; waiting for confirmation")
                return ManualRouteCommandResult.Accepted
            }

            requiresExplicitSelection = true
            status = ManualRouteStatus.FAILED
            errorCode = result.errorCode
            publish(reason = "User endpoint request failed; Telecom session must be rebuilt")
            return ManualRouteCommandResult.Failed
        } finally {
            stateMutex.unlock()
        }
    }

    suspend fun onCurrentEndpoint(endpoint: TelecomEndpoint) = stateMutex.withLock {
        current = endpoint
        if (!requiresExplicitSelection) updateStatusFromPlatform()
        publish(reason = "Telecom current endpoint confirmed")
    }

    suspend fun onInactive() = stateMutex.withLock {
        phase = TelecomCallPhase.INACTIVE
        publish(reason = "Telecom requested inactive; disconnecting")
    }

    suspend fun onActive() = stateMutex.withLock {
        phase = TelecomCallPhase.ACTIVE
        if (!requiresExplicitSelection) updateStatusFromPlatform()
        publish(reason = "Telecom call active")
    }

    suspend fun onDisconnected() = stateMutex.withLock {
        phase = TelecomCallPhase.STOPPED
        if (!requiresExplicitSelection) status = ManualRouteStatus.STARTING
        publish(reason = "Telecom call disconnected")
    }

    private fun updateStatusFromPlatform() {
        status = when {
            phase != TelecomCallPhase.ACTIVE -> ManualRouteStatus.STARTING
            current == null -> ManualRouteStatus.STARTING
            current?.identifier == selected?.identifier -> ManualRouteStatus.CONFIRMED
            else -> ManualRouteStatus.DIVERGED
        }
        errorCode = null
    }

    private fun resolveChoice(choice: AudioRouteChoice): TelecomEndpoint? =
        if (choice.target == AudioRouteTarget.DEVICE) {
            available.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        } else {
            available.firstOrNull { it.identifier == choice.endpointIdentifier }
        }

    private fun findAvailableEndpoint(endpoint: TelecomEndpoint): TelecomEndpoint? =
        if (endpoint.type == CallEndpointCompat.TYPE_SPEAKER) {
            available.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        } else {
            available.firstOrNull { it.identifier == endpoint.identifier }
        }

    private fun AudioRouteChoice.toTelecomEndpointPlaceholder(): TelecomEndpoint = TelecomEndpoint(
        identifier = endpointIdentifier ?: DEVICE_AUDIO_ROUTE_KEY,
        name = name,
        type = endpointType,
    )

    private fun publishRouteState() {
        publishRouteStatus(status, errorCode)
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
            ),
            reason,
        )
        publishRouteState()
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
