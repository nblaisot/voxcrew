package com.nblaisot.voxcrew.lanlink

/**
 * Pure path preference for LAN ↔ Tailscale. Discovery feeds sightings; TCP dial
 * success/failure owns whether we stay on LAN or move to overlay.
 */
object OverlayFailoverPolicy {
    enum class PathAction {
        /** Prefer / keep LAN as the active dial target. */
        USE_LAN,
        /** Use overlay endpoint (LAN absent, or LAN dial already failed). */
        USE_OVERLAY,
        /** Discovery quiet but TCP is healthy — leave the active session alone. */
        KEEP_SESSION,
        /** No discovery path and no healthy session — clear dial target. */
        CLEAR,
    }

    data class Decision(
        val action: PathAction,
    )

    /**
     * @param lanDialFailed true after at least one failed dial to the LAN target while
     *   an overlay endpoint was/is known — LAN beacon presence must not trap us there.
     */
    fun decide(
        lanSighting: LanPeer?,
        hasOverlayEndpoint: Boolean,
        activeVia: String?,
        sessionHealthy: Boolean,
        lanDialFailed: Boolean = false,
    ): Decision {
        // Healthy TCP is never torn down solely because UDP presence went quiet.
        if (sessionHealthy && activeVia == PathLabels.LOCAL) {
            return Decision(PathAction.KEEP_SESSION)
        }
        if (sessionHealthy && activeVia == PathLabels.VPN && lanSighting == null) {
            return Decision(PathAction.KEEP_SESSION)
        }

        val lan = lanSighting?.takeUnless { it.viaOverlay }
        if (lan != null && !(lanDialFailed && hasOverlayEndpoint)) {
            return Decision(PathAction.USE_LAN)
        }
        if (hasOverlayEndpoint) {
            return Decision(PathAction.USE_OVERLAY)
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
