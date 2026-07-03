package com.nblaisot.voxcrew.connectivity.quality

import com.nblaisot.voxcrew.connectivity.model.ConnectivityThresholds
import com.nblaisot.voxcrew.connectivity.model.PathQuality
import com.nblaisot.voxcrew.connectivity.model.TransportMode
import java.time.Instant

class MutablePeerPathEvaluator(
    private val thresholds: ConnectivityThresholds = ConnectivityThresholds(),
) : PeerPathEvaluator {
    var manualMode = false
    private val qualities = mutableMapOf(
        TransportMode.LOCAL_LAN to PathQuality(),
        TransportMode.CLOUD_DIRECT to PathQuality(),
        TransportMode.CLOUD_RELAY to PathQuality(),
    )

    fun set(mode: TransportMode, quality: PathQuality) {
        qualities[mode] = quality
    }

    override fun evaluate(
        mode: TransportMode,
        probeSuccess: Boolean,
        rttMs: Long?,
        packetLossRatio: Double?,
        nowMs: Long,
    ): PathQuality {
        if (manualMode) return current(mode)
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

    override fun isCloudReachable(): Boolean =
        current(TransportMode.CLOUD_DIRECT).reachable || current(TransportMode.CLOUD_RELAY).reachable

    fun stableLocal(nowMs: Long = 0L) = set(
        TransportMode.LOCAL_LAN,
        PathQuality(
            reachable = true,
            rttMs = 50,
            consecutiveSuccesses = 5,
            observedDurationMs = 5_000,
            lastUpdatedAt = Instant.ofEpochMilli(nowMs),
        ),
    )

    fun degradedLocal(nowMs: Long = 0L) = set(
        TransportMode.LOCAL_LAN,
        PathQuality(
            reachable = false,
            consecutiveFailures = 5,
            observedDurationMs = 5_000,
            lastUpdatedAt = Instant.ofEpochMilli(nowMs),
        ),
    )

    fun stableCloud(nowMs: Long = 0L) = set(
        TransportMode.CLOUD_DIRECT,
        PathQuality(
            reachable = true,
            rttMs = 120,
            consecutiveSuccesses = 5,
            observedDurationMs = 5_000,
            lastUpdatedAt = Instant.ofEpochMilli(nowMs),
        ),
    )
}
