package com.nblaisot.voxcrew.lanlink

/**
 * Pure path preference when choosing a dial target:
 * Local → Cloud (UUID relay, when control WSS is ready) → VPN (ephemeral overlayHost).
 * Discovery remains LAN-only; Cloud never invents NEARBY without a link attempt.
 */
object OverlayFailoverPolicy {
    enum class PathAction {
        /** Prefer / keep LAN as the active dial target. */
        USE_LAN,
        /** Use overlay endpoint (LAN absent, or LAN dial already failed; Cloud not ready). */
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
     * @param lanDialFailed retained for call-site compatibility; ignored for path choice —
     *   path lock always prefers Local while a LAN beacon exists (failover is Local death).
     * @param hasCloudEndpoint true when relay settings are up and our control WSS is hello_ok.
     */
    @Suppress("UNUSED_PARAMETER")
    fun decide(
        lanSighting: LanPeer?,
        hasOverlayEndpoint: Boolean,
        activeVia: String?,
        sessionHealthy: Boolean,
        lanDialFailed: Boolean = false,
        hasCloudEndpoint: Boolean = false,
    ): Decision {
        // Healthy Local is never torn down solely because UDP presence went quiet.
        if (sessionHealthy && activeVia == PathLabels.LOCAL) {
            return Decision(PathAction.KEEP_SESSION)
        }
        // Healthy Cloud stays even if an overlay endpoint appears (no Cloud → VPN steal).
        if (sessionHealthy && activeVia == PathLabels.CLOUD && lanSighting == null) {
            return Decision(PathAction.KEEP_SESSION)
        }
        // Healthy VPN only when Cloud is not available (Cloud preferred off-LAN).
        if (sessionHealthy && activeVia == PathLabels.VPN &&
            lanSighting == null && !hasCloudEndpoint
        ) {
            return Decision(PathAction.KEEP_SESSION)
        }

        val lan = lanSighting?.takeUnless { it.viaOverlay }
        // Seeking is sequential: while a LAN beacon exists, dial Local only — never
        // start Cloud/VPN in parallel (path lock; icon/media stay on one pipe).
        if (lan != null) {
            return Decision(PathAction.USE_LAN)
        }
        // Off-LAN: Cloud before overlay when relay is ready.
        if (hasCloudEndpoint) {
            return Decision(PathAction.USE_CLOUD)
        }
        if (hasOverlayEndpoint) {
            return Decision(PathAction.USE_OVERLAY)
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
