package com.nblaisot.voxcrew.lanlink

/**
 * When a LOCAL TCP session dies, decide whether to lock onto overlay or keep dialing LAN.
 * Dual-dial races and path switches close LOCAL often while the LAN beacon is still fresh —
 * promoting overlay in that case causes reconnect flaps.
 */
object LocalLinkDeathPolicy {
    /**
     * @return true if overlay should be preferred (no usable LAN sighting).
     */
    fun shouldPromoteOverlay(
        lanSighting: LanPeer?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val lan = lanSighting?.takeUnless { it.viaOverlay } ?: return true
        if (lan.lastSeenMs <= 0L) return true
        return nowMs - lan.lastSeenMs > LanBeacon.STALE_MS
    }
}
