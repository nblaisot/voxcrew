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
import com.nblaisot.voxcrew.lanlink.LanIntercomEngine
import com.nblaisot.voxcrew.lanlink.PeerMetrics
import com.nblaisot.voxcrew.roster.CrewMember
import com.nblaisot.voxcrew.roster.CrewRosterRepository
import com.nblaisot.voxcrew.service.SessionForegroundService
import com.nblaisot.voxcrew.signaling.SignalingClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val localEmail: String? = null,
    val statusMessage: String = "Recherche de coéquipiers…",
    val bannerMessage: String? = null,
    val showAudioRetry: Boolean = false,
    val crew: List<CrewMember> = emptyList(),
    val activeRecipientUids: Set<String> = emptySet(),
    val receivingAudioFromUid: String? = null,
    val peerMetrics: Map<String, PeerMetrics> = emptyMap(),
    val voxEnabled: Boolean = false,
    val voxSensitivity: Int = VoxSensitivity.DEFAULT.level,
    val isTransmitting: Boolean = false,
    val pttEnabled: Boolean = false,
    val appForeground: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val bluetoothConnectGranted: Boolean = true,
    val audioRouteReady: Boolean = false,
    val audioStartAllowed: Boolean = true,
    val permissionPrompt: AudioPermissionIssue? = null,
    val audioRouteChoices: List<AudioRouteChoice> = listOf(deviceAudioRouteChoice()),
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
    private val signalingClient: SignalingClient,
    private val rosterRepository: CrewRosterRepository,
    private val lanEngine: LanIntercomEngine,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var intercomStarted = false

    init {
        viewModelScope.launch {
            combine(authRepository.currentUser, rosterRepository.members) { user, crew -> user?.email to crew }
                .collect { (email, crew) ->
                    _uiState.update { it.copy(localEmail = email, crew = crew) }
                    lanEngine.syncCrewPeers(crew.map { it.uid }.toSet())
                }
        }
        viewModelScope.launch {
            lanEngine.isTransmitting.collect { tx ->
                Log.d(TAG, "shouldTransmit=$tx")
                _uiState.update { it.copy(isTransmitting = tx) }
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
                _uiState.update { it.copy(activeRecipientUids = uids) }
            }
        }
        viewModelScope.launch {
            lanEngine.peerMetrics.collect { metrics ->
                _uiState.update { it.copy(peerMetrics = metrics) }
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
                lanEngine.appForeground,
            ) { selection, route, pipeline, input, appForeground ->
                AudioUiSnapshot(selection, route, pipeline, input, appForeground)
            }.collect { snapshot ->
                val selection = snapshot.selection
                val route = snapshot.route
                val pipeline = snapshot.pipeline
                val input = snapshot.input
                val appForeground = snapshot.appForeground
                val ready = isConfirmedDuplexReady(route, pipeline)
                val pipelineFailure = pipeline as? AudioPipelineState.Failed
                val startAllowed = route.sessionIssue == null && pipelineFailure == null
                val manualStatus = selection.status
                val confirmedChoice = selection.availableChoices.firstOrNull { choice ->
                    if (choice.key == DEVICE_AUDIO_ROUTE_KEY) {
                        route.currentEndpoint?.type == CallEndpointCompat.TYPE_SPEAKER
                    } else {
                        choice.endpointIdentifier == route.currentEndpoint?.identifier
                    }
                }
                val displayedInput = when {
                    ready -> input
                    route.currentEndpoint != null -> confirmedChoice?.inputKind ?: route.micKind
                    else -> selection.selectedChoice.inputKind
                }
                _uiState.update {
                    it.copy(
                        audioRouteChoices = selection.availableChoices,
                        selectedAudioRoute = selection.selectedChoice,
                        audioRouteStatus = manualStatus,
                        appForeground = appForeground,
                        audioRoutePending = appForeground &&
                            !it.voxEnabled &&
                            it.micPermissionGranted &&
                            startAllowed &&
                            (manualStatus == ManualRouteStatus.STARTING ||
                                manualStatus == ManualRouteStatus.REQUESTING),
                        pttMicIconKind = displayedInput,
                        audioRouteReady = ready,
                        audioStartAllowed = startAllowed,
                        bannerMessage = route.sessionIssue?.toUserMessage()
                            ?: pipelineFailure?.let { failure ->
                                "Audio indisponible : ${failure.reason}"
                            }
                            ?: manualStatus.toUserMessage(
                                selectedName = selection.selectedChoice.name,
                                currentName = route.currentEndpoint?.name,
                                errorCode = selection.errorCode,
                            ),
                        showAudioRetry = route.sessionIssue != null || pipelineFailure != null,
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
            lanEngine.onMicrophonePermissionDenied()
        }
        lanEngine.refreshAudioRouting()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun MainUiState.withPttEnabled(): MainUiState =
        copy(
            pttEnabled = computePttEnabled(
                voxEnabled = voxEnabled,
                appForeground = appForeground,
                micPermissionGranted = micPermissionGranted,
                audioRouteReady = audioRouteReady,
                audioStartAllowed = audioStartAllowed,
            ),
        )

    private fun startIntercom() {
        if (intercomStarted) return
        intercomStarted = true
        startForegroundIfAllowed()
        runCatching { signalingClient.connect() }
        viewModelScope.launch {
            val user = authRepository.currentUser.value ?: return@launch
            val uid = user.uid
            rosterRepository.start(uid, user.email)
            lanEngine.start(uid, user.email?.takeIf { it.isNotBlank() } ?: uid)
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
            lanEngine.onMicrophonePermissionGranted()
        } else {
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
        lanEngine.toggleRecipient(member.uid)
    }

    fun soloRecipient(member: CrewMember) {
        if (member.isSelf) return
        lanEngine.soloRecipient(member.uid)
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
        signalingClient.disconnect()
        lanEngine.releaseAudioSession()
        rosterRepository.stop()
        SessionForegroundService.stop(appContext)
        viewModelScope.launch { authRepository.signOut() }
    }

    fun quitApplication() {
        signalingClient.disconnect()
        lanEngine.shutdown()
        rosterRepository.stop()
        SessionForegroundService.stop(appContext)
    }

    private fun startForegroundIfAllowed() {
        SessionForegroundService.start(appContext)
    }

    private companion object {
        const val TAG = "VoxCrewVM"

        fun AudioSessionIssue.toUserMessage(): String = when (this) {
            AudioSessionIssue.TELECOM_UNAVAILABLE -> "Session audio Android indisponible"
            AudioSessionIssue.AUDIO_PIPELINE_FAILED -> "Le pipeline audio a rencontré une erreur"
        }

        fun ManualRouteStatus.toUserMessage(
            selectedName: String,
            currentName: String?,
            errorCode: Int?,
        ): String? = when (this) {
            ManualRouteStatus.DIVERGED ->
                "Android utilise « ${currentName ?: "une autre sortie"} ». " +
                    "Choisissez la sortie voulue dans le menu audio."
            ManualRouteStatus.UNAVAILABLE ->
                "La sortie « $selectedName » n'est plus disponible. Choisissez une sortie audio."
            ManualRouteStatus.FAILED ->
                "Android a refusé « $selectedName »" +
                    (errorCode?.let { " (code $it)" } ?: "") +
                    ". Choisissez une sortie audio pour reconstruire la session."
            ManualRouteStatus.STARTING,
            ManualRouteStatus.REQUESTING,
            ManualRouteStatus.CONFIRMED -> null
        }
    }
}

private data class AudioUiSnapshot(
    val selection: com.nblaisot.voxcrew.audio.AudioRouteSelectionState,
    val route: com.nblaisot.voxcrew.audio.TelecomCallState,
    val pipeline: AudioPipelineState,
    val input: CaptureInputKind,
    val appForeground: Boolean,
)

internal fun computePttEnabled(
    voxEnabled: Boolean,
    appForeground: Boolean,
    micPermissionGranted: Boolean,
    audioRouteReady: Boolean,
    audioStartAllowed: Boolean,
): Boolean = !voxEnabled &&
    appForeground &&
    micPermissionGranted &&
    audioRouteReady &&
    audioStartAllowed
