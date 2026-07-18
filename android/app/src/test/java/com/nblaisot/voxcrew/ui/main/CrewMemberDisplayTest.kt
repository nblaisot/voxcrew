package com.nblaisot.voxcrew.ui.main

import com.nblaisot.voxcrew.lanlink.PeerLink
import com.nblaisot.voxcrew.roster.MemberAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class CrewMemberDisplayTest {

    @Test
    fun `connected relay path shows active cloud even when roster says offline`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = "Relais cloud",
            linkState = PeerLink.LinkState.Connected("peer-b", "Relais cloud"),
        )
        assertEquals(MemberAvailability.ONLINE_CLOUD, result)
    }

    @Test
    fun `connected vpn path shows overlay availability`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = "VPN",
            linkState = PeerLink.LinkState.Connected("peer-b", "VPN"),
        )
        assertEquals(MemberAvailability.ONLINE_OVERLAY, result)
    }

    @Test
    fun `connected local path shows wifi icon`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.OFFLINE,
            pathLabel = "Local",
            linkState = PeerLink.LinkState.Connected("peer-b", "Local"),
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
            pathLabel = "Local",
            linkState = PeerLink.LinkState.Disconnected("peer-b"),
        )
        assertEquals(MemberAvailability.OFFLINE, result)
    }

    @Test
    fun `connecting link state does not inherit roster online hint`() {
        val result = displayAvailability(
            rosterAvailability = MemberAvailability.ONLINE_CLOUD,
            pathLabel = null,
            linkState = PeerLink.LinkState.Connecting("peer-b"),
        )
        assertEquals(MemberAvailability.OFFLINE, result)
    }
}
