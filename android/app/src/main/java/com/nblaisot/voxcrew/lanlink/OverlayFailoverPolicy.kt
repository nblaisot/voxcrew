package com.nblaisot.voxcrew.lanlink

/**
 * Pure timing policy for LAN → Tailscale failover.
 * Detection uses [LanBeacon] intervals; this decides when to warm standby vs promote.
 */
object OverlayFailoverPolicy {
    fun shouldWarmStandby(
        lanLastSeenMs: Long?,
        nowMs: Long,
        hasOverlayEndpoint: Boolean,
        lanStillListed: Boolean,
    ): Boolean {
        if (!hasOverlayEndpoint || !lanStillListed) return false
        val lastSeen = lanLastSeenMs ?: return false
        val age = nowMs - lastSeen
        return age >= LanBeacon.MISSED_BEACON_MS
    }

    fun shouldPromoteOverlay(
        lanStillListed: Boolean,
        hasOverlayEndpoint: Boolean,
    ): Boolean = !lanStillListed && hasOverlayEndpoint

    /** Prefer LAN whenever a non-overlay sighting is present. */
    fun preferLan(lanPeer: LanPeer?, overlayPeer: LanPeer?): LanPeer? = when {
        lanPeer != null && !lanPeer.viaOverlay -> lanPeer
        overlayPeer != null -> overlayPeer
        else -> lanPeer
    }
}
