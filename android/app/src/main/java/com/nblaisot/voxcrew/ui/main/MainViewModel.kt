package com.nblaisot.voxcrew.ui.main

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.lanlink.LanIntercomEngine
import com.nblaisot.voxcrew.lanlink.PeerLink
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
    val selectedPeerUid: String? = null,
    val receivingAudioFromUid: String? = null,
    val selectedPeerRttMs: Long? = null,
    val selectedPeerPathLabel: String? = null,
    val selectedPeerBacklogMs: Long = 0L,
    val voxEnabled: Boolean = false,
    val isTransmitting: Boolean = false,
    val pttEnabled: Boolean = true,
    val micPermissionGranted: Boolean = false,
)

/**
 * Local-mode-first: this screen is now a thin observer/controller of
 * [LanIntercomEngine], which owns discovery, the TCP link, capture and playback and
 * keeps running independently of this ViewModel's lifecycle (see
 * [com.nblaisot.voxcrew.di.AppContainer]). Cloud fallback (UDP hole punch
 * and WebSocket relay) is handled inside the engine when LAN is unavailable.
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
                .collect { (email, crew) -> _uiState.update { it.copy(localEmail = email, crew = crew) } }
        }
        viewModelScope.launch {
            rosterRepository.members.collect { crew -> maybeAutoSelectSolePeer(crew) }
        }
        viewModelScope.launch {
            lanEngine.isTransmitting.collect { tx ->
                Log.d(TAG, "shouldTransmit=$tx")
                _uiState.update { it.copy(isTransmitting = tx) }
            }
        }
        viewModelScope.launch {
            combine(lanEngine.isReceiving, lanEngine.selectedPeerUid) { receiving, peer ->
                if (receiving) peer else null
            }.collect { peer -> _uiState.update { it.copy(receivingAudioFromUid = peer) } }
        }
        viewModelScope.launch {
            lanEngine.statusText.collect { status -> _uiState.update { it.copy(statusMessage = status) } }
        }
        viewModelScope.launch {
            // Source of truth for the standing target is the engine (it persists and
            // restores it across launches); mirror it into the roster and UI state here.
            lanEngine.selectedPeerUid.collect { uid ->
                rosterRepository.select(uid)
                _uiState.update { it.copy(selectedPeerUid = uid) }
            }
        }
        viewModelScope.launch {
            lanEngine.rttMs.collect { rtt -> _uiState.update { it.copy(selectedPeerRttMs = rtt) } }
        }
        viewModelScope.launch {
            lanEngine.backlogMs.collect { backlog -> _uiState.update { it.copy(selectedPeerBacklogMs = backlog) } }
        }
        viewModelScope.launch {
            lanEngine.linkState.collect { link ->
                val label = (link as? PeerLink.LinkState.Connected)?.via
                _uiState.update { it.copy(selectedPeerPathLabel = label) }
            }
        }
        startIntercom()
    }

    private fun startIntercom() {
        if (intercomStarted) return
        intercomStarted = true
        startForegroundIfAllowed()
        // Best-effort: cloud presence is a nice-to-have for the roster, never
        // required — local discovery works even if this never connects.
        runCatching { signalingClient.connect() }
        viewModelScope.launch {
            val user = authRepository.currentUser.value ?: return@launch
            val uid = user.uid
            rosterRepository.start(uid, user.email)
            lanEngine.start(uid, user.email?.takeIf { it.isNotBlank() } ?: uid)
        }
    }

    fun onPermissionsResult(results: Map<String, Boolean>) {
        val micGranted = results[android.Manifest.permission.RECORD_AUDIO] == true
        _uiState.update { it.copy(micPermissionGranted = micGranted) }
        if (micGranted) startForegroundIfAllowed()
    }

    fun selectCrewMember(member: CrewMember) {
        if (member.isSelf) return
        // Roster selection and ui state follow reactively from lanEngine.selectedPeerUid.
        lanEngine.selectPeer(member.uid)
    }

    private var solePeerAutoConnectAttempted = false

    private fun maybeAutoSelectSolePeer(crew: List<CrewMember>) {
        if (crew.size != 1) {
            solePeerAutoConnectAttempted = false
            return
        }
        if (_uiState.value.selectedPeerUid != null) return
        if (solePeerAutoConnectAttempted) return
        solePeerAutoConnectAttempted = true
        selectCrewMember(crew.first())
    }

    fun setVoxEnabled(enabled: Boolean) {
        _uiState.update { it.copy(voxEnabled = enabled, pttEnabled = !enabled) }
        lanEngine.setVoxEnabled(enabled)
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
    }
}
