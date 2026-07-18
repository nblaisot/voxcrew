package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveRecipientPolicyTest {

    @Test
    fun `opt-in mode keeps persisted recipients when crew roster is still empty`() {
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
    fun `legacy mode selects all crew when active set is empty`() {
        val result = ActiveRecipientPolicy.recipientsAfterCrewSync(
            currentActive = emptySet(),
            crewUids = setOf("peer-a", "peer-b"),
            previousKnownCrew = emptySet(),
            optInMode = false,
        )
        assertEquals(setOf("peer-a", "peer-b"), result)
    }
}
