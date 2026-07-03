package com.nblaisot.voxcrew.connectivity.model

import java.time.Instant

data class PathQuality(
    val reachable: Boolean = false,
    val rttMs: Long? = null,
    val packetLossRatio: Double? = null,
    val consecutiveSuccesses: Int = 0,
    val consecutiveFailures: Int = 0,
    val observedDurationMs: Long = 0,
    val lastUpdatedAt: Instant = Instant.now(),
) {
    fun isStableForHandover(thresholds: ConnectivityThresholds): Boolean =
        reachable &&
            consecutiveSuccesses >= 3 &&
            observedDurationMs >= thresholds.localCandidateValidationMs &&
            (rttMs == null || rttMs <= thresholds.localMaxRttMs) &&
            (packetLossRatio == null || packetLossRatio <= thresholds.localMaxPacketLossRatio)
}
