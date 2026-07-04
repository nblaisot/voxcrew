package com.nblaisot.voxcrew.roster

import java.security.MessageDigest

object PairSessionIds {
    fun forUids(uidA: String, uidB: String): String {
        val sorted = listOf(uidA, uidB).sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest("${sorted[0]}:${sorted[1]}".toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
