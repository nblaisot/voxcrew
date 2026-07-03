package com.nblaisot.voxcrew.connectivity.model

data class ConnectivityThresholds(
    val localCandidateValidationMs: Long = 4_000,
    val localProbeIntervalMs: Long = 1_000,
    val localFailureTimeoutMs: Long = 2_000,
    val localMaxRttMs: Long = 400,
    val localMaxPacketLossRatio: Double = 0.20,
    val cloudPreparationTimeoutMs: Long = 10_000,
    val switchCooldownMs: Long = 5_000,
    val localFailureSamplesBeforeCloud: Int = 3,
)
