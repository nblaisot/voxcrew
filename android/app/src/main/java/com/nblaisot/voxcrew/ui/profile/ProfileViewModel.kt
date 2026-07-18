package com.nblaisot.voxcrew.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nblaisot.voxcrew.auth.LocalProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val displayName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isConfigured: Boolean = false,
)

class ProfileViewModel(
    private val profileRepository: LocalProfileRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.currentUser.collect { user ->
                _uiState.update {
                    it.copy(
                        displayName = user?.displayName.orEmpty(),
                        isConfigured = user != null,
                    )
                }
            }
        }
    }

    fun onDisplayNameChange(value: String) {
        _uiState.update { it.copy(displayName = value, error = null) }
    }

    fun saveProfile() {
        val name = _uiState.value.displayName
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Choisissez un nom") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            profileRepository.saveProfile(name)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, isConfigured = true) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Impossible d'enregistrer le profil",
                        )
                    }
                }
        }
    }
}
