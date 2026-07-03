package com.nblaisot.voxcrew.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nblaisot.voxcrew.BuildConfig
import com.nblaisot.voxcrew.auth.AuthRepository
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
    val lastError: String? = null,
    val lastRttMs: Long? = null,
)

class HomeViewModel(
    private val authRepository: AuthRepository,
    private val signalingClient: SignalingClient,
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

    fun createSession(onReady: (String) -> Unit) {
        viewModelScope.launch {
            signalingClient.createSession("session")
                .onSuccess(onReady)
                .onFailure { err -> _uiState.update { it.copy(lastError = err.message) } }
        }
    }

    fun joinSession(onReady: (String) -> Unit) {
        val id = _uiState.value.joinSessionId.trim()
        if (id.isBlank()) return
        viewModelScope.launch {
            signalingClient.joinSession(id)
                .onSuccess { onReady(id) }
                .onFailure { err -> _uiState.update { it.copy(lastError = err.message) } }
        }
    }

    fun signOut() {
        signalingClient.disconnect()
        viewModelScope.launch { authRepository.signOut() }
    }
}
