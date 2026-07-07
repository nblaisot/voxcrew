package com.nblaisot.voxcrew.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nblaisot.voxcrew.audio.AudioPermissionIssue
import com.nblaisot.voxcrew.audio.AudioRoute
import com.nblaisot.voxcrew.audio.AudioRouteSelector
import com.nblaisot.voxcrew.audio.CaptureInputKind
import com.nblaisot.voxcrew.audio.VoxSensitivity
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
    val crew: List<CrewMember> = emptyList(),
    val activeRecipientUids: Set<String> = emptySet(),
    val receivingAudioFromUid: String? = null,
    val peerMetrics: Map<String, PeerMetrics> = emptyMap(),
    val voxEnabled: Boolean = false,
    val voxSensitivity: Int = VoxSensitivity.DEFAULT.level,
    val isTransmitting: Boolean = false,
    val pttEnabled: Boolean = true,
    val micPermissionGranted: Boolean = false,
    val bluetoothConnectGranted: Boolean = true,
    val audioRouteReady: Boolean = false,
    val permissionPrompt: AudioPermissionIssue? = null,
    val pttMicIconKind: CaptureInputKind? = null,
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
            lanEngine.audioRoute.collect { route ->
                _uiState.update {
                    it.copy(
                        pttMicIconKind = pttMicIconKind(route),
                        audioRouteReady = route.routeReady,
                        permissionPrompt = route.permissionIssue
                            ?: it.permissionPrompt?.takeIf { prompt ->
                                prompt == AudioPermissionIssue.RECORD_AUDIO && !it.micPermissionGranted
                            },
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
            lanEngine.onMicrophonePermissionGranted()
        }
        lanEngine.refreshAudioRouting()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun MainUiState.withPttEnabled(): MainUiState =
        copy(
            pttEnabled = !voxEnabled &&
                micPermissionGranted &&
                audioRouteReady &&
                permissionPrompt == null,
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
                    it.permissionPrompt == AudioPermissionIssue.BLUETOOTH_CONNECT && btGranted -> null
                    else -> it.permissionPrompt
                },
            ).withPttEnabled()
        }
        if (micGranted) {
            startForegroundIfAllowed()
            lanEngine.onMicrophonePermissionGranted()
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
            lanEngine.refreshAudioRouting()
        }
    }

    fun dismissPermissionPrompt() {
        _uiState.update { it.copy(permissionPrompt = null).withPttEnabled() }
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
        if (!_uiState.value.pttEnabled) return
        lanEngine.pttRelease()
    }

    fun signOut() {
        signalingClient.disconnect()
        SessionForegroundService.stop(appContext)
        viewModelScope.launch { authRepository.signOut() }
    }

    private fun startForegroundIfAllowed() {
        if (!_uiState.value.micPermissionGranted) return
        SessionForegroundService.start(appContext)
    }

    private companion object {
        const val TAG = "VoxCrewVM"

        fun pttMicIconKind(route: AudioRoute): CaptureInputKind? =
            AudioRouteSelector.pttMicIconKind(route)
    }
}
