package com.nblaisot.voxcrew.signaling

data class PresenceMember(
    val uid: String,
    val email: String,
    val transportHint: String = "cloud",
    val online: Boolean = true,
    val lastSeenMs: Long? = null,
)
