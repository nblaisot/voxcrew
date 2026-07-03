package com.nblaisot.voxcrew.signaling

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    RECONNECTING,
}

data class SignalingUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val sessionId: String? = null,
    val participants: List<String> = emptyList(),
    val localUid: String? = null,
    val lastError: String? = null,
    val lastRttMs: Long? = null,
)
