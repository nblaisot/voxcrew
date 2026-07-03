package com.nblaisot.voxcrew.connectivity.state

import com.nblaisot.voxcrew.connectivity.model.ConnectivityFailure
import com.nblaisot.voxcrew.connectivity.model.PathQuality
import com.nblaisot.voxcrew.connectivity.model.TransportMode

sealed interface ConnectivityState {
    data object Idle : ConnectivityState
    data object Discovering : ConnectivityState
    data class ConnectingLocal(val generation: Long) : ConnectivityState
    data class LocalActive(val generation: Long, val quality: PathQuality) : ConnectivityState
    data class ConnectingCloud(val generation: Long) : ConnectivityState
    data class CloudActive(
        val generation: Long,
        val transportMode: TransportMode,
        val quality: PathQuality,
    ) : ConnectivityState
    data class TransitioningToLocal(
        val previousGeneration: Long,
        val candidateGeneration: Long,
    ) : ConnectivityState
    data class TransitioningToCloud(
        val previousGeneration: Long,
        val candidateGeneration: Long,
    ) : ConnectivityState
    data class Reconnecting(
        val preferredTransport: TransportMode?,
        val attempt: Int,
    ) : ConnectivityState
    data class Failed(val reason: ConnectivityFailure) : ConnectivityState
}

enum class TransportPreference {
    AUTO,
    FORCE_LOCAL,
    FORCE_CLOUD,
}

data class ConnectivityDiagnostics(
    val activeTransport: TransportMode = TransportMode.NONE,
    val connectivityState: ConnectivityState = ConnectivityState.Idle,
    val activeGeneration: Long? = null,
    val candidateGeneration: Long? = null,
    val localAddress: String? = null,
    val localPeerDiscovered: Boolean = false,
    val localRttMs: Long? = null,
    val localPacketLoss: Double? = null,
    val cloudSignalingConnected: Boolean = false,
    val lastSwitchReason: String? = null,
    val lastSwitchAtMs: Long? = null,
)
