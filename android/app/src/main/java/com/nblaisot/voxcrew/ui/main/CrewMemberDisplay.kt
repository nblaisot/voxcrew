package com.nblaisot.voxcrew.ui.main

import com.nblaisot.voxcrew.lanlink.PeerLink
import com.nblaisot.voxcrew.roster.MemberAvailability

/**
 * Roster [MemberAvailability] reflects presence/beacon hints and can stay OFFLINE for a
 * peer that is already reachable over an established audio link (e.g. cloud relay).
 * When [linkState] is connected, derive the icon from the live path label instead.
 */
internal fun displayAvailability(
    rosterAvailability: MemberAvailability,
    pathLabel: String?,
    linkState: PeerLink.LinkState?,
): MemberAvailability {
    when (linkState) {
        is PeerLink.LinkState.Connected -> return when (pathLabel) {
            "Local" -> MemberAvailability.ONLINE_LOCAL
            "VPN" -> MemberAvailability.ONLINE_OVERLAY
            else -> MemberAvailability.ONLINE_CLOUD
        }
        is PeerLink.LinkState.Disconnected -> return MemberAvailability.OFFLINE
        is PeerLink.LinkState.Connecting -> return MemberAvailability.OFFLINE
        else -> Unit
    }
    return rosterAvailability
}
