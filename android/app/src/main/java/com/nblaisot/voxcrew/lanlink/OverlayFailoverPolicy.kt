package com.nblaisot.voxcrew.lanlink

/**
 * Pure path preference for LAN ↔ Tailscale. Discovery feeds sightings; TCP session
 * health owns whether an active link stays up.
 */
object OverlayFailoverPolicy {
    enum class PathAction {
        /** Prefer / keep LAN as the active dial target; optionally warm overlay standby. */
        USE_LAN,
        /** No live LAN sighting — use overlay endpoint (promote or dial). */
        USE_OVERLAY,
        /** Discovery quiet but TCP is healthy — leave the active session alone. */
        KEEP_SESSION,
        /** No discovery path and no healthy session — clear dial target. */
        CLEAR,
    }

    data class Decision(
        val action: PathAction,
        val warmStandby: Boolean = false,
    )

    fun decide(
        lanSighting: LanPeer?,
        hasOverlayEndpoint: Boolean,
        nowMs: Long,
        activeVia: String?,
        sessionHealthy: Boolean,
    ): Decision {
        val lan = lanSighting?.takeUnless { it.viaOverlay }
        if (lan != null) {
            val warm = shouldWarmStandby(
                lanLastSeenMs = lan.lastSeenMs,
                nowMs = nowMs,
                hasOverlayEndpoint = hasOverlayEndpoint,
                lanStillListed = true,
            )
            return Decision(PathAction.USE_LAN, warmStandby = warm)
        }
        if (hasOverlayEndpoint) {
            return Decision(PathAction.USE_OVERLAY)
        }
        // Healthy TCP must not be torn down solely because discovery went quiet.
        if (sessionHealthy && activeVia != null) {
            return Decision(PathAction.KEEP_SESSION)
        }
        return Decision(PathAction.CLEAR)
    }

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
