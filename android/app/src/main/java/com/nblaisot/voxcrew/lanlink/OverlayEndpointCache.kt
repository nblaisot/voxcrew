package com.nblaisot.voxcrew.lanlink

import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped Tailscale dial endpoints (`uid → 100.x:port`).
 *
 * Not discovery / NEARBY and never roster or SharedPreferences. Sources are LAN
 * beacon `overlayHost` and ephemeral relay gossip; cleared when local overlay is gone.
 */
internal data class OverlayEndpoint(
    val host: String,
    val port: Int,
)

internal class OverlayEndpointCache {
    private val byUid = ConcurrentHashMap<String, OverlayEndpoint>()

    val uids: Set<String>
        get() = byUid.keys.toSet()

    fun get(uid: String): OverlayEndpoint? = byUid[uid]

    fun put(uid: String, host: String, port: Int) {
        val h = host.trim()
        if (uid.isBlank() || h.isBlank() || port !in 1..65_535) return
        byUid[uid] = OverlayEndpoint(host = h, port = port)
    }

    /** Learn from a LAN beacon that carries peer Tailscale metadata. */
    fun harvestFromBeacon(peer: LanPeer) {
        val host = peer.overlayHost?.takeIf { it.isNotBlank() } ?: return
        put(peer.uid, host, peer.port)
    }

    fun clear() {
        byUid.clear()
    }
}

/**
 * Live beacon overlay metadata first; else a dial-target [LanPeer] from the session
 * cache when local Tailscale is up. The result is for [routePeer] only — not presence.
 */
internal fun overlayDialTarget(
    uid: String,
    displayName: String,
    sighting: LanPeer?,
    cached: OverlayEndpoint?,
    overlayNetworkPresent: Boolean,
): LanPeer? {
    beaconOverlayDialTarget(sighting)?.let { return it }
    if (!overlayNetworkPresent || cached == null) return null
    return LanPeer(
        uid = uid,
        displayName = displayName,
        host = cached.host,
        port = cached.port,
        lastSeenMs = sighting?.lastSeenMs ?: 0L,
        overlayHost = cached.host,
        viaOverlay = true,
    )
}

internal fun beaconOverlayDialTarget(peer: LanPeer?): LanPeer? {
    if (peer == null) return null
    if (peer.viaOverlay) return peer.copy(overlayHost = peer.overlayHost ?: peer.host)
    val overlayHost = peer.overlayHost?.takeIf { it.isNotBlank() } ?: return null
    return peer.copy(host = overlayHost, overlayHost = overlayHost, viaOverlay = true)
}
