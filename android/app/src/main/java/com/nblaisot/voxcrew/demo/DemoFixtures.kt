package com.nblaisot.voxcrew.demo

import androidx.core.telecom.CallEndpointCompat
import com.nblaisot.voxcrew.audio.TelecomEndpoint
import com.nblaisot.voxcrew.roster.CrewMember
import com.nblaisot.voxcrew.roster.MemberAvailability

object DemoFixtures {
    const val MARC_UID = "demo-marc"
    const val ANNE_UID = "demo-anne"
    const val QUENTIN_UID = "demo-quentin"

    const val EARBUDS_ID = "demo-earbuds"
    const val WATCH_ID = "demo-watch"

    const val EARBUDS_NAME = "Nicolas' earbuds"
    const val WATCH_NAME = "Galaxy Watch 8"

    val allUids: Set<String> = setOf(MARC_UID, ANNE_UID, QUENTIN_UID)

    fun isDemoUid(uid: String): Boolean = uid in allUids

    fun isDemoAudioRouteKey(key: String): Boolean =
        key == audioRouteKey(EARBUDS_ID) || key == audioRouteKey(WATCH_ID)

    fun audioRouteKey(endpointId: String): String = "bt:${endpointId.uppercase()}"

    fun seededMembers(nowMs: Long = System.currentTimeMillis()): List<CrewMember> = listOf(
        CrewMember(
            uid = MARC_UID,
            displayName = "Marc",
            availability = MemberAvailability.ONLINE_LOCAL,
            lastSeenMs = nowMs,
            isActiveRecipient = true,
        ),
        CrewMember(
            uid = ANNE_UID,
            displayName = "Anne",
            availability = MemberAvailability.ONLINE_OVERLAY,
            lastSeenMs = nowMs,
            isActiveRecipient = false,
        ),
        CrewMember(
            uid = QUENTIN_UID,
            displayName = "Quentin",
            availability = MemberAvailability.OFFLINE,
            lastSeenMs = nowMs - 3_600_000L,
            isActiveRecipient = false,
        ),
    )

    fun bluetoothEndpoints(): List<TelecomEndpoint> = listOf(
        TelecomEndpoint(
            EARBUDS_ID,
            EARBUDS_NAME,
            CallEndpointCompat.TYPE_BLUETOOTH,
            bluetoothAddress = EARBUDS_ID,
        ),
        TelecomEndpoint(
            WATCH_ID,
            WATCH_NAME,
            CallEndpointCompat.TYPE_BLUETOOTH,
            bluetoothAddress = WATCH_ID,
        ),
    )
}

object DemoRosterPolicy {
    fun mergeIntoCrew(real: List<CrewMember>, demo: List<CrewMember>): List<CrewMember> {
        val realUids = real.map { it.uid }.toSet()
        val extras = demo.filter { it.uid !in realUids }
        return (real + extras).sortedBy { it.displayName.lowercase() }
    }

    fun realCrewUids(crewUids: Set<String>): Set<String> =
        crewUids.filterNot { DemoFixtures.isDemoUid(it) }.toSet()

    fun afterToggle(members: List<CrewMember>, uid: String): List<CrewMember> {
        if (!DemoFixtures.isDemoUid(uid)) return members
        return members.map { member ->
            if (member.uid != uid) member
            else member.copy(isActiveRecipient = !member.isActiveRecipient)
        }
    }

    fun afterSolo(members: List<CrewMember>, uid: String): List<CrewMember> {
        if (!DemoFixtures.isDemoUid(uid)) return members
        return members.map { member ->
            if (!DemoFixtures.isDemoUid(member.uid)) member
            else member.copy(isActiveRecipient = member.uid == uid)
        }
    }

    fun afterForget(members: List<CrewMember>, uid: String): List<CrewMember> {
        if (!DemoFixtures.isDemoUid(uid)) return members
        return members.filter { it.uid != uid }
    }
}
