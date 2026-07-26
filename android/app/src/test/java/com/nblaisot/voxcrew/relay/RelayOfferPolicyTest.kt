package com.nblaisot.voxcrew.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayOfferPolicyTest {

    private val link = RelayConfigLink(
        url = "wss://mini.example:8443",
        secret = "crew",
        certSha256 = "abcd",
    )

    @Test
    fun `surfaces offer when local unconfigured`() {
        val offer = RelayOfferPolicy.decide(
            localConfigured = false,
            incoming = link,
            peerUid = "peer-a",
            peerDisplayName = "Alice",
            dismissedPeerUids = emptySet(),
        )
        assertNotNull(offer)
        assertEquals("peer-a", offer!!.peerUid)
        assertEquals("Alice", offer.peerDisplayName)
        assertEquals(link, offer.link)
    }

    @Test
    fun `ignores when local already configured`() {
        assertNull(
            RelayOfferPolicy.decide(
                localConfigured = true,
                incoming = link,
                peerUid = "peer-a",
                peerDisplayName = "Alice",
                dismissedPeerUids = emptySet(),
            ),
        )
    }

    @Test
    fun `ignores dismissed peer for the session`() {
        assertNull(
            RelayOfferPolicy.decide(
                localConfigured = false,
                incoming = link,
                peerUid = "peer-a",
                peerDisplayName = "Alice",
                dismissedPeerUids = setOf("peer-a"),
            ),
        )
    }

    @Test
    fun `ignores when already showing same peer`() {
        assertNull(
            RelayOfferPolicy.decide(
                localConfigured = false,
                incoming = link,
                peerUid = "peer-a",
                peerDisplayName = "Alice",
                dismissedPeerUids = emptySet(),
                pendingPeerUid = "peer-a",
            ),
        )
    }

    @Test
    fun `ignores blank incoming`() {
        assertNull(
            RelayOfferPolicy.decide(
                localConfigured = false,
                incoming = null,
                peerUid = "peer-a",
                peerDisplayName = "Alice",
                dismissedPeerUids = emptySet(),
            ),
        )
    }
}
