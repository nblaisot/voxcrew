package com.nblaisot.voxcrew.lanlink

data class LanPeer(
    val uid: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val lastSeenMs: Long,
    /** Tailscale / overlay IPv4 learned from beacon, used when LAN host is unavailable. */
    val overlayHost: String? = null,
    val viaOverlay: Boolean = false,
)
