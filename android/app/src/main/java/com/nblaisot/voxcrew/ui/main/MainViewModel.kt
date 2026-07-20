package com.nblaisot.voxcrew.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallEndpointCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nblaisot.voxcrew.R
import com.nblaisot.voxcrew.audio.AudioPermissionIssue
import com.nblaisot.voxcrew.audio.AudioPipelineState
import com.nblaisot.voxcrew.audio.AudioRouteChoice
import com.nblaisot.voxcrew.audio.AudioSessionIssue
import com.nblaisot.voxcrew.audio.CaptureInputKind
import com.nblaisot.voxcrew.audio.DEVICE_AUDIO_ROUTE_KEY
import com.nblaisot.voxcrew.audio.ManualRouteStatus
import com.nblaisot.voxcrew.audio.VoxSensitivity
import com.nblaisot.voxcrew.audio.deviceAudioRouteChoice
import com.nblaisot.voxcrew.audio.isConfirmedDuplexReady
import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.auth.LocalProfileRepository
import com.nblaisot.voxcrew.demo.DemoFixtures
import com.nblaisot.voxcrew.demo.DemoModeStore
import com.nblaisot.voxcrew.demo.DemoRosterPolicy
import com.nblaisot.voxcrew.lanlink.LanIntercomEngine
import com.nblaisot.voxcrew.lanlink.PeerLink
import com.nblaisot.voxcrew.lanlink.PeerMetrics
import com.nblaisot.voxcrew.roster.CrewMember
import com.nblaisot.voxcrew.roster.CrewRosterRepository
import com.nblaisot.voxcrew.service.SessionForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val localDisplayName: String? = null,
    val statusMessage: String = "",
    val bannerMessage: String? = null,
    val showAudioRetry: Boolean = false,
    val showUseThisDevice: Boolean = false,
    val crew: List<CrewMember> = emptyList(),
    val activeRecipientUids: Set<String> = emptySet(),
    val receivingAudioFromUid: String? = null,
    val peerMetrics: Map<String, PeerMetrics> = emptyMap(),
    val voxEnabled: Boolean = false,
    val voxSensitivity: Int = VoxSensitivity.DEFAULT.level,
    val isTransmitting: Boolean = false,
    val pttEnabled: Boolean = false,
    val pttBlockReason: PttBlockReason = PttBlockReason.Pending,
    val appForeground: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val bluetoothConnectGranted: Boolean = true,
    val audioRouteReady: Boolean = false,
    val audioStartAllowed: Boolean = true,
    val permissionPrompt: AudioPermissionIssue? = null,
    val audioRouteChoices: List<AudioRouteChoice> = emptyList(),
    val selectedAudioRoute: AudioRouteChoice = deviceAudioRouteChoice(),
    val audioRouteStatus: ManualRouteStatus = ManualRouteStatus.STARTING,
    val audioRoutePending: Boolean = false,
    val pttMicIconKind: CaptureInputKind = CaptureInputKind.BUILTIN,
)

/**
 * Local-mode-first: this screen is a thin observer/controller of
 * [LanIntercomEngine], which owns discovery, per-peer links, capture fan-out
 * and playback and keeps running independently of this ViewModel's lifecycle.
 */
class MainViewModel(
    private val appContext: Context,
    private val authRepository: AuthRepository,
    private val localProfileRepository: LocalProfileRepository,
    private val rosterRepository: CrewRosterRepository,
    private val lanEngine: LanIntercomEngine,
    private val demoModeStore: DemoModeStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        MainUiState(
            statusMessage = appContext.getString(R.string.status_searching_crewmates),
            audioRouteChoices = listOf(
                deviceAudioRouteChoice(
                    name = appContext.getString(R.string.audio_route_this_device),
                ),
            ),
            selectedAudioRoute = deviceAudioRouteChoice(
                name = appContext.getString(R.string.audio_route_this_device),
            ),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var intercomStarted = false

    private fun AudioSessionIssue.toUserMessage(): String = when (this) {
        AudioSessionIssue.TELECOM_UNAVAILABLE ->
            appContext.getString(R.string.audio_session_unavailable)
        AudioSessionIssue.AUDIO_PIPELINE_FAILED ->
            appContext.getString(R.string.audio_pipeline_error)
    }

    private fun ManualRouteStatus.toUserMessage(
        selectedName: String,
        currentName: String?,
        errorCode: Int?,
    ): String? = when (this) {
        ManualRouteStatus.DIVERGED ->
            appContext.getString(
                R.string.audio_route_diverged,
                currentName ?: appContext.getString(R.string.another_output),
            )
        ManualRouteStatus.UNAVAILABLE ->
            appContext.getString(R.string.audio_route_unavailable, selectedName)
        ManualRouteStatus.FAILED ->
            appContext.getString(
                R.string.audio_route_failed,
                selectedName,
                errorCode?.let { appContext.getString(R.string.audio_route_failed_code, it) }.orEmpty(),
            )
        ManualRouteStatus.STARTING,
        ManualRouteStatus.REQUESTING,
        ManualRouteStatus.CONFIRMED -> null
    }

    init {
        viewModelScope.launch {
            combine(
                authRepository.currentUser,
                rosterRepository.members,
                demoModeStore.enabled,
                demoModeStore.demoMembers,
            ) { user, crew, demoEnabled, demoMembers ->
                val displayCrew = if (demoEnabled) {
                    DemoRosterPolicy.mergeIntoCrew(crew, demoMembers)
                } else {
                    crew
                }
                Triple(user?.label, displayCrew, DemoRosterPolicy.realCrewUids(displayCrew.map { it.uid }.toSet()))
            }.collect { (label, displayCrew, realUids) ->
                _uiState.update { it.copy(localDisplayName = label, crew = displayCrew) }
                lanEngine.syncCrewPeers(realUids)
            }
        }
        viewModelScope.launch {
            demoModeStore.enabled.collect { enabled ->
                if (!enabled) return@collect
                // Play Store fixtures: VOX on + earbuds so the PTT control shows the BT icon.
                lanEngine.setVoxEnabled(true)
                lanEngine.selectAudioRoute(DemoFixtures.audioRouteKey(DemoFixtures.EARBUDS_ID))
            }
        }
        viewModelScope.launch {
            lanEngine.isTransmitting.collect { tx ->
                Log.d(TAG, "shouldTransmit=$tx")
                _uiState.update { it.copy(isTransmitting = tx).withPttEnabled() }
            }
        }
        viewModelScope.launch {
            lanEngine.receivingFromUids.collect { uids ->
                _uiState.update { it.copy(receivingAudioFromUid = uids.firstOrNull()) }
            }
        }
        viewModelScope.launch {
            lanEngine.statusText.collect { status -> _uiState.update { it.copy(statusMessage = status) } }
        }
        viewModelScope.launch {
            lanEngine.activeRecipientUids.collect { uids ->
                rosterRepository.setActiveRecipients(uids)
                _uiState.update { it.copy(activeRecipientUids = uids).withPttEnabled() }
            }
        }
        viewModelScope.launch {
            lanEngine.peerMetrics.collect { metrics ->
                _uiState.update { it.copy(peerMetrics = metrics).withPttEnabled() }
            }
        }
        viewModelScope.launch {
            lanEngine.voxEnabled.collect { enabled ->
                _uiState.update { it.copy(voxEnabled = enabled).withPttEnabled() }
            }
        }
        viewModelScope.launch {
            lanEngine.voxSensitivity.collect { sensitivity ->
                _uiState.update { it.copy(voxSensitivity = sensitivity.level) }
            }
        }
        viewModelScope.launch {
            combine(
                lanEngine.audioRouteSelection,
                lanEngine.audioRoute,
                lanEngine.audioPipelineState,
                lanEngine.captureInputKind,
                combine(lanEngine.appForeground, demoModeStore.enabled) { foreground, demo ->
                    foreground to demo
                },
            ) { selection, route, pipeline, input, foregroundAndDemo ->
                val (appForeground, demoEnabled) = foregroundAndDemo
                AudioUiSnapshot(selection, route, pipeline, input, appForeground, demoEnabled)
            }.collect { snapshot ->
                val selection = snapshot.selection
                val route = snapshot.route
                val pipeline = snapshot.pipeline
                val input = snapshot.input
                val appForeground = snapshot.appForeground
                val demoEnabled = snapshot.demoEnabled
                val demoRoute = DemoFixtures.isDemoAudioRouteKey(selection.selectedChoice.key)
                val ready = isConfirmedDuplexReady(route, pipeline) || (demoEnabled && demoRoute)
                val pipelineFailure = pipeline as? AudioPipelineState.Failed
                val pipelineOpening = pipeline is AudioPipelineState.Opening
                val startAllowed = route.sessionIssue == null && pipelineFailure == null
                val manualStatus = when {
                    demoEnabled && demoRoute -> ManualRouteStatus.CONFIRMED
                    else -> selection.status
                }
                val confirmedChoice = selection.availableChoices.firstOrNull { choice ->
                    if (choice.key == DEVICE_AUDIO_ROUTE_KEY) {
                        route.currentEndpoint?.type == CallEndpointCompat.TYPE_SPEAKER
                    } else {
                        choice.endpointIdentifier == route.currentEndpoint?.identifier ||
                            (
                                choice.bluetoothAddress != null &&
                                    choice.bluetoothAddress == route.currentEndpoint?.bluetoothAddress
                                )
                    }
                }
                val displayedInput = when {
                    demoEnabled && demoRoute -> selection.selectedChoice.inputKind
                    ready -> input
                    route.currentEndpoint != null -> confirmedChoice?.inputKind ?: route.micKind
                    else -> selection.selectedChoice.inputKind
                }
                val rawBanner = route.sessionIssue?.toUserMessage()
                    ?: pipelineFailure?.let { failure ->
                        appContext.getString(R.string.audio_unavailable_reason, failure.reason)
                    }
                    ?: manualStatus.toUserMessage(
                        selectedName = selection.selectedChoice.name,
                        currentName = route.currentEndpoint?.name,
                        errorCode = selection.errorCode,
                    )
                _uiState.update {
                    val awaitingDuplex = appForeground &&
                        !it.voxEnabled &&
                        it.micPermissionGranted &&
                        startAllowed &&
                        !ready &&
                        (manualStatus == ManualRouteStatus.STARTING ||
                            manualStatus == ManualRouteStatus.REQUESTING ||
                            pipelineOpening ||
                            pipeline is AudioPipelineState.Closed)
                    it.copy(
                        audioRouteChoices = selection.availableChoices,
                        selectedAudioRoute = selection.selectedChoice,
                        audioRouteStatus = manualStatus,
                        appForeground = appForeground,
                        audioRoutePending = awaitingDuplex,
                        pttMicIconKind = displayedInput,
                        audioRouteReady = ready,
                        audioStartAllowed = startAllowed,
                        // Demo fixtures must not show Telecom "unavailable" / pipeline banners.
                        bannerMessage = if (demoEnabled) null else rawBanner,
                        showAudioRetry = !demoEnabled &&
                            (route.sessionIssue != null || pipelineFailure != null),
                        // One-tap recovery: accessory lost/diverged -> offer "This device".
                        showUseThisDevice = !demoEnabled &&
                            selection.selectedChoice.key != DEVICE_AUDIO_ROUTE_KEY &&
                            (
                                manualStatus == ManualRouteStatus.UNAVAILABLE ||
                                    manualStatus == ManualRouteStatus.DIVERGED ||
                                    manualStatus == ManualRouteStatus.FAILED
                                ),
                    ).withPttEnabled()
                }
            }
        }
        refreshPermissions()
        startIntercom()
    }

    fun refreshPermissions() {
        val micGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        val btGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        _uiState.update {
            it.copy(
                micPermissionGranted = micGranted,
                bluetoothConnectGranted = btGranted,
                permissionPrompt = when {
                    !micGranted -> AudioPermissionIssue.RECORD_AUDIO
                    it.permissionPrompt == AudioPermissionIssue.BLUETOOTH_CONNECT && btGranted -> null
                    else -> it.permissionPrompt
                },
            ).withPttEnabled()
        }
        if (micGranted) {
            startForegroundIfAllowed()
            SessionForegroundService.refreshForegroundTypes(appContext)
            lanEngine.onMicrophonePermissionGranted()
        } else {
            // FGS must stop advertising the microphone type once the permission is gone.
            SessionForegroundService.refreshForegroundTypes(appContext)
            lanEngine.onMicrophonePermissionDenied()
        }
        lanEngine.refreshAudioRouting()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun MainUiState.withPttEnabled(): MainUiState {
        val hasActiveRecipient = activeRecipientUids.isNotEmpty()
        val hasConnectedRecipient = activeRecipientUids.any { uid ->
            peerMetrics[uid]?.linkState is PeerLink.LinkState.Connected
        }
        val reason = resolvePttBlockReason(
            voxEnabled = voxEnabled,
            appForeground = appForeground,
            micPermissionGranted = micPermissionGranted,
            audioRouteReady = audioRouteReady,
            audioStartAllowed = audioStartAllowed,
            audioRoutePending = audioRoutePending,
            audioRouteStatus = audioRouteStatus,
            showAudioRetry = showAudioRetry,
            hasActiveRecipient = hasActiveRecipient,
            hasConnectedRecipient = hasConnectedRecipient,
            isTransmitting = isTransmitting,
        )
        return copy(
            pttBlockReason = reason,
            pttEnabled = pttEnabledForReason(reason),
        )
    }

    /** Restarts the mesh if it was stopped via the notification's "Leave session". */
    fun ensureIntercomRunning() {
        if (intercomStarted && !lanEngine.isStarted) intercomStarted = false
        startIntercom()
    }

    private fun startIntercom() {
        if (intercomStarted) return
        intercomStarted = true
        startForegroundIfAllowed()
        viewModelScope.launch {
            val user = authRepository.currentUser.value ?: return@launch
            rosterRepository.start(user.uid, user.label)
            lanEngine.seedOverlayProbeHosts(rosterRepository.cachedOverlayHosts())
            lanEngine.start(user.uid, user.label)
        }
    }

    fun onPermissionsResult(results: Map<String, Boolean>) {
        val micGranted = results[Manifest.permission.RECORD_AUDIO]
            ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            results[Manifest.permission.BLUETOOTH_CONNECT]
                ?: hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
        _uiState.update {
            it.copy(
                micPermissionGranted = micGranted,
                bluetoothConnectGranted = btGranted,
                permissionPrompt = when {
                    !micGranted -> AudioPermissionIssue.RECORD_AUDIO
                    !btGranted -> AudioPermissionIssue.BLUETOOTH_CONNECT
                    it.permissionPrompt == AudioPermissionIssue.BLUETOOTH_CONNECT && btGranted -> null
                    else -> it.permissionPrompt
                },
            ).withPttEnabled()
        }
        if (micGranted) {
            startForegroundIfAllowed()
            SessionForegroundService.refreshForegroundTypes(appContext)
            lanEngine.onMicrophonePermissionGranted()
        } else {
            // FGS must stop advertising the microphone type once the permission is gone.
            SessionForegroundService.refreshForegroundTypes(appContext)
            lanEngine.onMicrophonePermissionDenied()
        }
        if (btGranted) {
            lanEngine.onBluetoothPermissionGranted()
        } else {
            lanEngine.refreshAudioRouting()
        }
    }

    fun onBluetoothPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                bluetoothConnectGranted = granted,
                permissionPrompt = if (granted) null else AudioPermissionIssue.BLUETOOTH_CONNECT,
            ).withPttEnabled()
        }
        if (granted) {
            lanEngine.onBluetoothPermissionGranted()
        } else {
            lanEngine.refreshAudioRouting()
        }
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                micPermissionGranted = granted,
                permissionPrompt = if (granted) null else AudioPermissionIssue.RECORD_AUDIO,
            ).withPttEnabled()
        }
        if (granted) {
            startForegroundIfAllowed()
            SessionForegroundService.refreshForegroundTypes(appContext)
            lanEngine.onMicrophonePermissionGranted()
        } else {
            SessionForegroundService.refreshForegroundTypes(appContext)
            lanEngine.onMicrophonePermissionDenied()
        }
    }

    fun dismissPermissionPrompt() {
        _uiState.update { it.copy(permissionPrompt = null).withPttEnabled() }
    }

    fun retryAudio() {
        lanEngine.retryAudioPipeline()
    }

    fun selectAudioRoute(key: String) {
        lanEngine.selectAudioRoute(key)
    }

    fun toggleRecipient(member: CrewMember) {
        if (member.isSelf) return
        if (DemoFixtures.isDemoUid(member.uid)) {
            demoModeStore.toggleRecipient(member.uid)
            return
        }
        lanEngine.toggleRecipient(member.uid)
    }

    fun soloRecipient(member: CrewMember) {
        if (member.isSelf) return
        if (DemoFixtures.isDemoUid(member.uid)) {
            demoModeStore.soloRecipient(member.uid)
            return
        }
        lanEngine.soloRecipient(member.uid)
    }

    fun forgetMember(member: CrewMember) {
        if (member.isSelf) return
        if (DemoFixtures.isDemoUid(member.uid)) {
            demoModeStore.forgetMember(member.uid)
            return
        }
        lanEngine.removeRecipient(member.uid)
        rosterRepository.forgetMember(member.uid)
    }

    fun setVoxEnabled(enabled: Boolean) {
        lanEngine.setVoxEnabled(enabled)
    }

    fun setVoxSensitivity(level: Int) {
        lanEngine.setVoxSensitivity(VoxSensitivity.coerce(level))
    }

    fun pttPress() {
        Log.d(TAG, "pttPress (enabled=${_uiState.value.pttEnabled})")
        if (!_uiState.value.pttEnabled) return
        lanEngine.pttPress()
    }

    fun pttRelease() {
        Log.d(TAG, "pttRelease (enabled=${_uiState.value.pttEnabled})")
        lanEngine.pttRelease()
    }

    fun signOut() {
        // Full mesh shutdown: releaseAudioSession() alone left beacon/TCP/dial loops
        // polling on the app scope after the user left.
        lanEngine.shutdown()
        intercomStarted = false
        rosterRepository.stop()
        SessionForegroundService.stop(appContext)
        viewModelScope.launch {
            localProfileRepository.signOut()
        }
    }

    fun quitApplication() {
        lanEngine.shutdown()
        rosterRepository.stop()
        SessionForegroundService.stop(appContext)
    }

    private fun startForegroundIfAllowed() {
        SessionForegroundService.start(appContext)
    }

    private companion object {
        const val TAG = "VoxCrewVM"
    }
}

private data class AudioUiSnapshot(
    val selection: com.nblaisot.voxcrew.audio.AudioRouteSelectionState,
    val route: com.nblaisot.voxcrew.audio.TelecomCallState,
    val pipeline: AudioPipelineState,
    val input: CaptureInputKind,
    val appForeground: Boolean,
    val demoEnabled: Boolean = false,
)

internal fun computePttEnabled(
    voxEnabled: Boolean,
    appForeground: Boolean,
    micPermissionGranted: Boolean,
    audioRouteReady: Boolean,
    audioStartAllowed: Boolean,
    audioRoutePending: Boolean = false,
    audioRouteStatus: ManualRouteStatus = ManualRouteStatus.CONFIRMED,
    showAudioRetry: Boolean = false,
    hasActiveRecipient: Boolean = true,
    hasConnectedRecipient: Boolean = true,
    isTransmitting: Boolean = false,
): Boolean = pttEnabledForReason(
    resolvePttBlockReason(
        voxEnabled = voxEnabled,
        appForeground = appForeground,
        micPermissionGranted = micPermissionGranted,
        audioRouteReady = audioRouteReady,
        audioStartAllowed = audioStartAllowed,
        audioRoutePending = audioRoutePending,
        audioRouteStatus = audioRouteStatus,
        showAudioRetry = showAudioRetry,
        hasActiveRecipient = hasActiveRecipient,
        hasConnectedRecipient = hasConnectedRecipient,
        isTransmitting = isTransmitting,
    ),
)
