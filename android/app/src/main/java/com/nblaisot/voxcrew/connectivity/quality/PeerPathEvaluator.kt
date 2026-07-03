package com.nblaisot.voxcrew.connectivity.quality

import com.nblaisot.voxcrew.connectivity.model.ConnectivityThresholds
import com.nblaisot.voxcrew.connectivity.model.PathQuality
import com.nblaisot.voxcrew.connectivity.model.TransportMode
import java.time.Instant

interface PeerPathEvaluator {
    fun evaluate(
        mode: TransportMode,
        probeSuccess: Boolean,
        rttMs: Long?,
        packetLossRatio: Double?,
        nowMs: Long = System.currentTimeMillis(),
    ): PathQuality

    fun current(mode: TransportMode): PathQuality

    fun isLocalDegraded(thresholds: ConnectivityThresholds, nowMs: Long = System.currentTimeMillis()): Boolean

    fun isLocalStable(thresholds: ConnectivityThresholds): Boolean

    fun isCloudReachable(): Boolean
}

class PeerPathEvaluatorImpl(
    private val thresholds: ConnectivityThresholds = ConnectivityThresholds(),
) : PeerPathEvaluator {
    private val qualities = mutableMapOf(
        TransportMode.LOCAL_LAN to PathQuality(),
        TransportMode.CLOUD_DIRECT to PathQuality(),
        TransportMode.CLOUD_RELAY to PathQuality(),
    )

    override fun evaluate(
        mode: TransportMode,
        probeSuccess: Boolean,
        rttMs: Long?,
        packetLossRatio: Double?,
        nowMs: Long,
    ): PathQuality {
        val previous = qualities[mode] ?: PathQuality()
        val updated = if (probeSuccess) {
            previous.copy(
                reachable = true,
                rttMs = rttMs ?: previous.rttMs,
                packetLossRatio = packetLossRatio ?: previous.packetLossRatio,
                consecutiveSuccesses = previous.consecutiveSuccesses + 1,
                consecutiveFailures = 0,
                observedDurationMs = previous.observedDurationMs + thresholds.localProbeIntervalMs,
                lastUpdatedAt = Instant.ofEpochMilli(nowMs),
            )
        } else {
            previous.copy(
                reachable = false,
                consecutiveFailures = previous.consecutiveFailures + 1,
                consecutiveSuccesses = 0,
                observedDurationMs = previous.observedDurationMs + thresholds.localProbeIntervalMs,
                lastUpdatedAt = Instant.ofEpochMilli(nowMs),
            )
        }
        qualities[mode] = updated
        return updated
    }

    override fun current(mode: TransportMode): PathQuality = qualities[mode] ?: PathQuality()

    override fun isLocalDegraded(thresholds: ConnectivityThresholds, nowMs: Long): Boolean {
        val q = current(TransportMode.LOCAL_LAN)
        return !q.reachable ||
            q.consecutiveFailures >= thresholds.localFailureSamplesBeforeCloud ||
            (q.rttMs != null && q.rttMs > thresholds.localMaxRttMs) ||
            (q.packetLossRatio != null && q.packetLossRatio > thresholds.localMaxPacketLossRatio)
    }

    override fun isLocalStable(thresholds: ConnectivityThresholds): Boolean =
        current(TransportMode.LOCAL_LAN).isStableForHandover(thresholds)

    override fun isCloudReachable(): Boolean = current(TransportMode.CLOUD_DIRECT).reachable ||
        current(TransportMode.CLOUD_RELAY).reachable
}
