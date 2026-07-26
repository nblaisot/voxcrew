package com.nblaisot.voxcrew.relay

/**
 * Pure policy for LAN Hello relay-config offers.
 * Never overwrite an existing local relay; dedup dismissed peers for the session.
 */
object RelayOfferPolicy {
    data class Offer(
        val peerUid: String,
        val peerDisplayName: String,
        val link: RelayConfigLink,
    )

    /**
     * @return the offer to surface in UI, or null to ignore.
     */
    fun decide(
        localConfigured: Boolean,
        incoming: RelayConfigLink?,
        peerUid: String,
        peerDisplayName: String,
        dismissedPeerUids: Set<String>,
        pendingPeerUid: String? = null,
    ): Offer? {
        if (localConfigured) return null
        val link = incoming ?: return null
        if (link.url.isBlank() || link.secret.isBlank()) return null
        if (peerUid in dismissedPeerUids) return null
        if (pendingPeerUid == peerUid) return null // already showing
        return Offer(
            peerUid = peerUid,
            peerDisplayName = peerDisplayName.ifBlank { peerUid },
            link = link,
        )
    }
}
