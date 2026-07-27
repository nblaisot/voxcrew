package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveRecipientPolicyTest {

    @Test
    fun `opt-in mode keeps session recipients when crew roster is briefly empty`() {
        val result = ActiveRecipientPolicy.recipientsAfterCrewSync(
            currentActive = setOf("peer-a"),
            crewUids = emptySet(),
            previousKnownCrew = emptySet(),
            optInMode = true,
        )
        assertEquals(setOf("peer-a"), result)
    }

    @Test
    fun `opt-in mode keeps active recipients unchanged on crew sync`() {
        val result = ActiveRecipientPolicy.recipientsAfterCrewSync(
            currentActive = setOf("peer-a"),
            crewUids = setOf("peer-a", "peer-b", "peer-c"),
            previousKnownCrew = setOf("peer-a"),
            optInMode = true,
        )
        assertEquals(setOf("peer-a"), result)
    }

    @Test
    fun `legacy mode auto-includes new peers`() {
        val result = ActiveRecipientPolicy.recipientsAfterCrewSync(
            currentActive = setOf("peer-a"),
            crewUids = setOf("peer-a", "peer-b"),
            previousKnownCrew = setOf("peer-a"),
            optInMode = false,
        )
        assertEquals(setOf("peer-a", "peer-b"), result)
    }

    @Test
    fun `connected peer is auto-included unless opted out`() {
        assertEquals(
            setOf("peer-a"),
            ActiveRecipientPolicy.recipientsAfterConnected(
                currentActive = emptySet(),
                connectedUid = "peer-a",
                optedOut = emptySet(),
            ),
        )
        assertEquals(
            emptySet<String>(),
            ActiveRecipientPolicy.recipientsAfterConnected(
                currentActive = emptySet(),
                connectedUid = "peer-a",
                optedOut = setOf("peer-a"),
            ),
        )
        assertEquals(
            setOf("peer-a"),
            ActiveRecipientPolicy.recipientsAfterConnected(
                currentActive = setOf("peer-a"),
                connectedUid = "peer-a",
                optedOut = emptySet(),
            ),
        )
    }
}
