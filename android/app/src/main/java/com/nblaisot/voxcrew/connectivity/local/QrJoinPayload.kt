package com.nblaisot.voxcrew.connectivity.local

import android.net.Uri

data class QrJoinPayload(
    val host: String,
    val port: Int,
    val sessionId: String,
    val token: String,
) {
    fun toUri(): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority("join-local")
        .appendQueryParameter("host", host)
        .appendQueryParameter("port", port.toString())
        .appendQueryParameter("sessionId", sessionId)
        .appendQueryParameter("token", token)
        .build()

    fun toDisplayHost(): String = "$host:$port"

    companion object {
        const val SCHEME = "voxcrew"

        fun fromUri(uri: Uri): QrJoinPayload? {
            if (uri.scheme != SCHEME) return null
            val host = uri.getQueryParameter("host") ?: return null
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
            val sessionId = uri.getQueryParameter("sessionId") ?: return null
            val token = uri.getQueryParameter("token") ?: return null
            return QrJoinPayload(host, port, sessionId, token)
        }

        fun fromManual(host: String, port: Int, sessionId: String, token: String): QrJoinPayload =
            QrJoinPayload(host.trim(), port, sessionId.trim(), token.trim())
    }
}
