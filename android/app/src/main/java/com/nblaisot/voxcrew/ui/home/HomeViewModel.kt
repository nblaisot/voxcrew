package com.nblaisot.voxcrew.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nblaisot.voxcrew.BuildConfig
import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.connectivity.local.QrJoinPayload
import com.nblaisot.voxcrew.di.AppContainer
import com.nblaisot.voxcrew.signaling.ConnectionState
import com.nblaisot.voxcrew.signaling.SignalingClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val userEmail: String? = null,
    val backendUrl: String = BuildConfig.SIGNALING_BASE_URL,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val joinSessionId: String = "",
    val manualHost: String = "",
    val manualPort: String = "38472",
    val manualToken: String = "",
    val lastError: String? = null,
    val lastRttMs: Long? = null,
    val localQrPayload: QrJoinPayload? = null,
    val showHotspotGuide: Boolean = false,
)

class HomeViewModel(
    private val authRepository: AuthRepository,
    private val signalingClient: SignalingClient,
    private val appContainer: AppContainer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(authRepository.currentUser, signalingClient.state) { user, signaling ->
                _uiState.value.copy(
                    userEmail = user?.email,
                    connectionState = signaling.connectionState,
                    lastError = signaling.lastError,
                    lastRttMs = signaling.lastRttMs,
                )
            }.collect { merged -> _uiState.value = merged }
        }
    }

    fun connectSignaling() {
        signalingClient.connect()
    }

    fun onJoinIdChange(value: String) = _uiState.update { it.copy(joinSessionId = value) }
    fun onManualHostChange(value: String) = _uiState.update { it.copy(manualHost = value) }
    fun onManualPortChange(value: String) = _uiState.update { it.copy(manualPort = value) }
    fun onManualTokenChange(value: String) = _uiState.update { it.copy(manualToken = value) }
    fun toggleHotspotGuide() = _uiState.update { it.copy(showHotspotGuide = !it.showHotspotGuide) }
    fun dismissQr() = _uiState.update { it.copy(localQrPayload = null) }

    fun createSession(onReady: (String, Boolean) -> Unit) {
        viewModelScope.launch {
            signalingClient.createSession("session")
                .onSuccess { id -> onReady(id, false) }
                .onFailure { err -> _uiState.update { it.copy(lastError = err.message) } }
        }
    }

    fun createLocalSession(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val uid = signalingClient.state.value.localUid
                ?: authRepository.currentUser.value?.uid
                ?: return@launch
            appContainer.createLocalSession(uid)
                .onSuccess { (id, qr) ->
                    _uiState.update { it.copy(localQrPayload = qr) }
                    onReady(id)
                }
                .onFailure { err -> _uiState.update { it.copy(lastError = err.message) } }
        }
    }

    fun joinSession(onReady: (String, Boolean) -> Unit) {
        val id = _uiState.value.joinSessionId.trim()
        if (id.isBlank()) return
        viewModelScope.launch {
            signalingClient.joinSession(id)
                .onSuccess { onReady(id, false) }
                .onFailure { err -> _uiState.update { it.copy(lastError = err.message) } }
        }
    }

    fun joinLocalFromQr(payload: QrJoinPayload, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val uid = signalingClient.state.value.localUid
                ?: authRepository.currentUser.value?.uid
                ?: return@launch
            appContainer.joinLocalSession(payload, uid)
                .onSuccess { onReady(payload.sessionId) }
                .onFailure { err -> _uiState.update { it.copy(lastError = err.message) } }
        }
    }

    fun joinLocalManual(onReady: (String) -> Unit) {
        val host = _uiState.value.manualHost.trim()
        val port = _uiState.value.manualPort.toIntOrNull() ?: return
        val sessionId = _uiState.value.joinSessionId.trim()
        val token = _uiState.value.manualToken.trim()
        if (host.isBlank() || sessionId.isBlank() || token.isBlank()) return
        joinLocalFromQr(QrJoinPayload.fromManual(host, port, sessionId, token), onReady)
    }

    fun signOut() {
        signalingClient.disconnect()
        viewModelScope.launch { authRepository.signOut() }
    }
}
