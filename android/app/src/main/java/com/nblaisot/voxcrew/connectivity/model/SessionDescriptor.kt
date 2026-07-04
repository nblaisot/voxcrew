package com.nblaisot.voxcrew.connectivity.model

data class SessionDescriptor(
    val sessionId: String,
    val participantId: String,
    val sessionSecret: String? = null,
    val hostParticipantId: String? = null,
    val isLocalHost: Boolean = false,
)
