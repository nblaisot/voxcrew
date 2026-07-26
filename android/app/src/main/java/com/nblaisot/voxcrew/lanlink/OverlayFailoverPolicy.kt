package com.nblaisot.voxcrew.lanlink

/**
 * Pure path preference: Local → VPN (ephemeral overlayHost) → Cloud (UUID relay).
 * Discovery remains LAN-only; Cloud never invents NEARBY.
 */
object OverlayFailoverPolicy {
    enum class PathAction {
        /** Prefer / keep LAN as the active dial target. */
        USE_LAN,
        /** Use overlay endpoint (LAN absent, or LAN dial already failed). */
        USE_OVERLAY,
        /** Dial peer UUID via the optional self-hosted relay. */
        USE_CLOUD,
        /** Discovery quiet but TCP/relay session is healthy — leave it alone. */
        KEEP_SESSION,
        /** No discovery path, no relay candidate, no healthy session — clear. */
        CLEAR,
    }

    data class Decision(
        val action: PathAction,
    )

    /**
     * @param lanDialFailed true after at least one failed dial to the LAN target while
     *   a better failover (overlay and/or cloud) is known.
     * @param hasCloudEndpoint true when relay settings are up and our control WSS is hello_ok.
     */
    fun decide(
        lanSighting: LanPeer?,
        hasOverlayEndpoint: Boolean,
        activeVia: String?,
        sessionHealthy: Boolean,
        lanDialFailed: Boolean = false,
        hasCloudEndpoint: Boolean = false,
    ): Decision {
        // Healthy path is never torn down solely because UDP presence went quiet.
        if (sessionHealthy && activeVia == PathLabels.LOCAL) {
            return Decision(PathAction.KEEP_SESSION)
        }
        if (sessionHealthy && activeVia == PathLabels.VPN && lanSighting == null) {
            return Decision(PathAction.KEEP_SESSION)
        }
        if (sessionHealthy && activeVia == PathLabels.CLOUD &&
            lanSighting == null && !hasOverlayEndpoint
        ) {
            return Decision(PathAction.KEEP_SESSION)
        }

        val lan = lanSighting?.takeUnless { it.viaOverlay }
        val failoverAvailable = hasOverlayEndpoint || hasCloudEndpoint
        if (lan != null && !(lanDialFailed && failoverAvailable)) {
            return Decision(PathAction.USE_LAN)
        }
        if (hasOverlayEndpoint) {
            return Decision(PathAction.USE_OVERLAY)
        }
        if (hasCloudEndpoint) {
            return Decision(PathAction.USE_CLOUD)
        }
        if (lan != null) {
            return Decision(PathAction.USE_LAN)
        }
        if (sessionHealthy && activeVia != null) {
            return Decision(PathAction.KEEP_SESSION)
        }
        return Decision(PathAction.CLEAR)
    }

    /** Prefer LAN whenever a non-overlay sighting is present. */
    fun preferLan(lanPeer: LanPeer?, overlayPeer: LanPeer?): LanPeer? = when {
        lanPeer != null && !lanPeer.viaOverlay -> lanPeer
        overlayPeer != null -> overlayPeer
        else -> lanPeer
    }
}
