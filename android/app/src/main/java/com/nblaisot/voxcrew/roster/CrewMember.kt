package com.nblaisot.voxcrew.roster

enum class MemberAvailability {
    ONLINE_LOCAL,
    ONLINE_CLOUD,
    OFFLINE,
}

data class CrewMember(
    val uid: String,
    val email: String,
    val availability: MemberAvailability,
    val lastSeenMs: Long? = null,
    val isSelf: Boolean = false,
    val isSelected: Boolean = false,
)
