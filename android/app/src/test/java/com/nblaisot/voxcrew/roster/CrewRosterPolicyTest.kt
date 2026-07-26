package com.nblaisot.voxcrew.roster

import com.nblaisot.voxcrew.lanlink.LanPeer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CrewRosterPolicyTest {
    @Test
    fun `repeated UUID updates one member instead of duplicating it`() {
        val merged = mergeKnownPeers(
            localUid = "self",
            knownPeers = emptyMap(),
            livePeers = listOf(
                peer(uid = "peer-a", name = "Old", host = "192.168.1.2", port = 47_101),
                peer(uid = "peer-a", name = "Alice", host = "192.168.1.3", port = 47_102),
            ),
            activeUids = emptySet(),
        )

        assertEquals(1, merged.members.size)
        assertEquals("Alice", merged.members.single().displayName)
        assertEquals(mapOf("peer-a" to "Alice"), merged.knownPeers)
    }

    @Test
    fun `new UUID is added and returning UUID restores the same row`() {
        val offline = mergeKnownPeers(
            localUid = "self",
            knownPeers = mapOf("peer-a" to "Alice"),
            livePeers = emptyList(),
            activeUids = emptySet(),
        )
        assertEquals(MemberAvailability.OFFLINE, offline.members.single().availability)
        assertNull(offline.members.single().lastSeenMs)

        val returned = mergeKnownPeers(
            localUid = "self",
            knownPeers = offline.knownPeers,
            livePeers = listOf(peer(uid = "peer-a", name = "Alice 2")),
            activeUids = emptySet(),
        )
        assertEquals(1, returned.members.size)
        assertEquals(MemberAvailability.ONLINE_LOCAL, returned.members.single().availability)
        assertEquals("Alice 2", returned.members.single().displayName)
    }

    @Test
    fun `known offline peers retain no endpoint metadata`() {
        val merged = mergeKnownPeers(
            localUid = "self",
            knownPeers = mapOf("peer-a" to "Alice"),
            livePeers = emptyList(),
            activeUids = setOf("peer-a"),
        )

        val member = merged.members.single()
        assertEquals("peer-a", member.uid)
        assertEquals(MemberAvailability.OFFLINE, member.availability)
        assertNull(member.lastSeenMs)
        assertEquals(mapOf("peer-a" to "Alice"), merged.knownPeers)
    }

    @Test
    fun `legacy cache migration keeps only UUID and display name`() {
        val migrated = decodeLegacyKnownPeers(
            raw = """[{"uid":"peer-a","displayName":"Alice","lastSeenMs":123,"overlayHost":"100.64.0.2"}]""",
            json = Json { ignoreUnknownKeys = true },
        )

        assertEquals(mapOf("peer-a" to "Alice"), migrated)
        assertFalse(migrated.values.any { it.contains("100.64") })
    }

    private fun peer(
        uid: String,
        name: String,
        host: String = "192.168.1.2",
        port: Int = 47_101,
    ) = LanPeer(
        uid = uid,
        displayName = name,
        host = host,
        port = port,
        lastSeenMs = 1_000L,
    )
}
