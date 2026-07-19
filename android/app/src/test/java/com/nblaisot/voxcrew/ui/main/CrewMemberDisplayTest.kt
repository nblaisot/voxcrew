package com.nblaisot.voxcrew.ui.main

import com.nblaisot.voxcrew.lanlink.PathLabels
import com.nblaisot.voxcrew.lanlink.PeerLink
import com.nblaisot.voxcrew.roster.MemberAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class CrewMemberDisplayTest {

    @Test
    fun `connected vpn path shows overlay availability`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = PathLabels.VPN,
            linkState = PeerLink.LinkState.Connected("peer-b", PathLabels.VPN),
        )
        assertEquals(MemberAvailability.ONLINE_OVERLAY, result)
    }

    @Test
    fun `connected local path shows wifi icon`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = PathLabels.LOCAL,
            linkState = PeerLink.LinkState.Connected("peer-b", PathLabels.LOCAL),
        )
        assertEquals(MemberAvailability.ONLINE_LOCAL, result)
    }

    @Test
    fun `unknown connected path defaults to local`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = "something-else",
            linkState = PeerLink.LinkState.Connected("peer-b", "something-else"),
        )
        assertEquals(MemberAvailability.ONLINE_LOCAL, result)
    }

    @Test
    fun `disconnected peer keeps roster availability`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = null,
            linkState = PeerLink.LinkState.Idle,
        )
        assertEquals(MemberAvailability.OFFLINE, result)
    }

    @Test
    fun `disconnected link state overrides roster online hint`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.ONLINE_LOCAL,
            pathLabel = PathLabels.LOCAL,
            linkState = PeerLink.LinkState.Disconnected("peer-b"),
        )
        assertEquals(MemberAvailability.OFFLINE, result)
    }

    @Test
    fun `connecting link state does not inherit roster online hint`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.ONLINE_OVERLAY,
            pathLabel = null,
            linkState = PeerLink.LinkState.Connecting("peer-b"),
        )
        assertEquals(MemberAvailability.OFFLINE, result)
    }
}
