package com.nblaisot.voxcrew.ui.main

import com.nblaisot.voxcrew.lanlink.PathLabels
import com.nblaisot.voxcrew.lanlink.PeerLink
import com.nblaisot.voxcrew.roster.MemberAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class CrewMemberDisplayTest {
    @Test
    fun connectedVpnShowsOverlay() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = PathLabels.VPN,
            linkState = PeerLink.LinkState.Connected("peer-b", PathLabels.VPN),
        )
        assertEquals(MemberAvailability.ONLINE_OVERLAY, result)
    }

    @Test
    fun connectedLocalShowsLocal() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = PathLabels.LOCAL,
            linkState = PeerLink.LinkState.Connected("peer-b", PathLabels.LOCAL),
        )
        assertEquals(MemberAvailability.ONLINE_LOCAL, result)
    }

    @Test
    fun connectedCloudShowsCloud() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = PathLabels.CLOUD,
            linkState = PeerLink.LinkState.Connected("peer-b", PathLabels.CLOUD),
        )
        assertEquals(MemberAvailability.ONLINE_CLOUD, result)
    }

    @Test
    fun connectedUnknownPathDefaultsToLocal() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = "something-else",
            linkState = PeerLink.LinkState.Connected("peer-b", "something-else"),
        )
        assertEquals(MemberAvailability.ONLINE_LOCAL, result)
    }

    @Test
    fun idleKeepsOffline() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = null,
            linkState = PeerLink.LinkState.Idle,
        )
        assertEquals(MemberAvailability.OFFLINE, result)
    }

    @Test
    fun disconnectedSightingShowsConnectingNotPathGlyph() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.ONLINE_LOCAL,
            pathLabel = PathLabels.LOCAL,
            linkState = PeerLink.LinkState.Disconnected("peer-b"),
        )
        assertEquals(MemberAvailability.NEARBY, result)
    }

    @Test
    fun connectingSightingShowsConnectingNotPathGlyph() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.ONLINE_OVERLAY,
            pathLabel = null,
            linkState = PeerLink.LinkState.Connecting("peer-b"),
        )
        assertEquals(MemberAvailability.NEARBY, result)
    }
}
