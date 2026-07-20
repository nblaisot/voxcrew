package com.nblaisot.voxcrew.demo

import com.nblaisot.voxcrew.roster.CrewMember
import com.nblaisot.voxcrew.roster.MemberAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRosterPolicyTest {

    @Test
    fun mergeAppendsDemoPeersSortedByName() {
        val real = listOf(
            member("alice", "Alice", MemberAvailability.ONLINE_LOCAL),
        )
        val demo = DemoFixtures.seededMembers(nowMs = 1_000L)
        val merged = DemoRosterPolicy.mergeIntoCrew(real, demo)

        assertEquals(
            listOf("Alice", "Anne", "Marc", "Quentin"),
            merged.map { it.displayName },
        )
    }

    @Test
    fun mergeSkipsDemoUidThatCollidesWithRealPeer() {
        val real = listOf(
            member(DemoFixtures.MARC_UID, "Real Marc", MemberAvailability.ONLINE_LOCAL),
        )
        val merged = DemoRosterPolicy.mergeIntoCrew(real, DemoFixtures.seededMembers())

        assertEquals(1, merged.count { it.uid == DemoFixtures.MARC_UID })
        assertEquals("Real Marc", merged.single { it.uid == DemoFixtures.MARC_UID }.displayName)
        assertTrue(merged.any { it.uid == DemoFixtures.ANNE_UID })
    }

    @Test
    fun realCrewUidsDropsDemoIds() {
        val uids = setOf("real-1", DemoFixtures.MARC_UID, DemoFixtures.ANNE_UID)
        assertEquals(setOf("real-1"), DemoRosterPolicy.realCrewUids(uids))
    }

    @Test
    fun seededMarcIsLocalAndIncluded() {
        val seeded = DemoFixtures.seededMembers()
        val marc = seeded.single { it.uid == DemoFixtures.MARC_UID }
        val anne = seeded.single { it.uid == DemoFixtures.ANNE_UID }
        val quentin = seeded.single { it.uid == DemoFixtures.QUENTIN_UID }

        assertEquals(MemberAvailability.ONLINE_LOCAL, marc.availability)
        assertTrue(marc.isActiveRecipient)
        assertEquals(MemberAvailability.ONLINE_OVERLAY, anne.availability)
        assertFalse(anne.isActiveRecipient)
        assertEquals(MemberAvailability.OFFLINE, quentin.availability)
        assertFalse(quentin.isActiveRecipient)
    }

    @Test
    fun toggleFlipsDemoRecipient() {
        val after = DemoRosterPolicy.afterToggle(DemoFixtures.seededMembers(), DemoFixtures.ANNE_UID)
        assertTrue(after.single { it.uid == DemoFixtures.ANNE_UID }.isActiveRecipient)
        assertTrue(after.single { it.uid == DemoFixtures.MARC_UID }.isActiveRecipient)
    }

    @Test
    fun soloKeepsOnlyTargetDemoRecipient() {
        val afterIncludeAnne = DemoRosterPolicy.afterToggle(DemoFixtures.seededMembers(), DemoFixtures.ANNE_UID)
        val afterSolo = DemoRosterPolicy.afterSolo(afterIncludeAnne, DemoFixtures.ANNE_UID)

        assertTrue(afterSolo.single { it.uid == DemoFixtures.ANNE_UID }.isActiveRecipient)
        assertFalse(afterSolo.single { it.uid == DemoFixtures.MARC_UID }.isActiveRecipient)
        assertFalse(afterSolo.single { it.uid == DemoFixtures.QUENTIN_UID }.isActiveRecipient)
    }

    @Test
    fun forgetRemovesDemoPeer() {
        val after = DemoRosterPolicy.afterForget(DemoFixtures.seededMembers(), DemoFixtures.QUENTIN_UID)
        assertFalse(after.any { it.uid == DemoFixtures.QUENTIN_UID })
        assertEquals(2, after.size)
    }

    @Test
    fun demoBluetoothKeysAreRecognized() {
        val endpoints = DemoFixtures.bluetoothEndpoints()
        assertEquals(
            listOf(DemoFixtures.EARBUDS_NAME, DemoFixtures.WATCH_NAME),
            endpoints.map { it.name },
        )
        endpoints.forEach { endpoint ->
            assertTrue(DemoFixtures.isDemoAudioRouteKey(DemoFixtures.audioRouteKey(endpoint.identifier)))
        }
    }

    @Test
    fun preferredEarbudsRouteKeyIsStable() {
        assertEquals(
            "bt:${DemoFixtures.EARBUDS_ID.uppercase()}",
            DemoFixtures.audioRouteKey(DemoFixtures.EARBUDS_ID),
        )
        assertTrue(DemoFixtures.isDemoAudioRouteKey(DemoFixtures.audioRouteKey(DemoFixtures.EARBUDS_ID)))
    }

    private fun member(
        uid: String,
        name: String,
        availability: MemberAvailability,
    ) = CrewMember(
        uid = uid,
        displayName = name,
        availability = availability,
    )
}
