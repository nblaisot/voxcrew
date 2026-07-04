package com.nblaisot.voxcrew.lanlink

data class LanPeer(
    val uid: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val lastSeenMs: Long,
)
