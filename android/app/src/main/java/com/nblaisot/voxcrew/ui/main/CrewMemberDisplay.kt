package com.nblaisot.voxcrew.ui.main

import com.nblaisot.voxcrew.lanlink.PathLabels
import com.nblaisot.voxcrew.lanlink.PeerLink
import com.nblaisot.voxcrew.roster.MemberAvailability

/**
 * Roster icon combines discovery hints with live audio-link state.
 * - Connected audio → show the live path (Local / VPN / Cloud).
 * - Discovery without Connected → [MemberAvailability.NEARBY], not a path glyph.
 * - Offline discovery → [MemberAvailability.OFFLINE].
 * Cloud registration alone never paints NEARBY.
 */
internal fun displayAvailability(
    rosterAvailability: MemberAvailability,
    pathLabel: String?,
    linkState: PeerLink.LinkState?,
): MemberAvailability {
    when (linkState) {
        is PeerLink.LinkState.Connected -> return when (pathLabel) {
            PathLabels.VPN -> MemberAvailability.ONLINE_OVERLAY
            PathLabels.CLOUD -> MemberAvailability.ONLINE_CLOUD
            else -> MemberAvailability.ONLINE_LOCAL
        }
        is PeerLink.LinkState.Disconnected,
        is PeerLink.LinkState.Connecting,
        PeerLink.LinkState.Idle,
        null,
        -> return when (rosterAvailability) {
            MemberAvailability.ONLINE_LOCAL,
            MemberAvailability.ONLINE_OVERLAY,
            MemberAvailability.ONLINE_CLOUD,
            MemberAvailability.NEARBY,
            -> MemberAvailability.NEARBY
            MemberAvailability.OFFLINE -> MemberAvailability.OFFLINE
        }
    }
}
