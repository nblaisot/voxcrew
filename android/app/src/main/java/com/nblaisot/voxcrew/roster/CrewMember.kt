package com.nblaisot.voxcrew.roster

enum class MemberAvailability {
    ONLINE_LOCAL,
    ONLINE_OVERLAY,
    OFFLINE,
}

data class CrewMember(
    val uid: String,
    val displayName: String,
    val email: String? = null,
    val availability: MemberAvailability,
    val lastSeenMs: Long? = null,
    val isSelf: Boolean = false,
    val isActiveRecipient: Boolean = false,
)
