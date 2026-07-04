package com.nblaisot.voxcrew.connectivity.local

import java.security.SecureRandom
import java.util.Base64

data class LocalSessionSecret(
    val sessionId: String,
    val token: String,
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    companion object {
        private const val TTL_MS = 30 * 60 * 1000L

        fun generate(sessionId: String): LocalSessionSecret {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return LocalSessionSecret(
                sessionId = sessionId,
                token = token,
                expiresAtMs = System.currentTimeMillis() + TTL_MS,
            )
        }

        fun validate(session: LocalSessionSecret, sessionId: String, token: String): Boolean =
            session.sessionId == sessionId && session.token == token && !session.isExpired()
    }
}
