package com.nblaisot.voxcrew.connectivity.model

enum class ConnectivityFailure {
    LOCAL_UNAVAILABLE,
    CLOUD_UNAVAILABLE,
    AUTH_FAILED,
    BOTH_PATHS_LOST,
    TRANSITION_FAILED,
    SESSION_EXPIRED,
}
