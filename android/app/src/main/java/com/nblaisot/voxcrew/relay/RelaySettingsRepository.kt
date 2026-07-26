package com.nblaisot.voxcrew.relay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RelaySettings(
    val enabled: Boolean = false,
    val url: String = "",
    val secret: String = "",
    val certSha256: String? = null,
) {
    val isConfigured: Boolean
        get() = enabled && url.isNotBlank() && secret.isNotBlank()
}

class RelaySettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<RelaySettings> = _settings.asStateFlow()

    fun current(): RelaySettings = _settings.value

    fun update(
        enabled: Boolean = _settings.value.enabled,
        url: String = _settings.value.url,
        secret: String = _settings.value.secret,
        certSha256: String? = _settings.value.certSha256,
    ) {
        val next = RelaySettings(
            enabled = enabled,
            url = url.trim(),
            secret = secret,
            certSha256 = certSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        )
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_URL, next.url)
            .putString(KEY_SECRET, next.secret)
            .putString(KEY_CERT, next.certSha256)
            .apply()
        _settings.value = next
    }

    fun applyLink(link: RelayConfigLink, enable: Boolean = true) {
        update(
            enabled = enable,
            url = link.url,
            secret = link.secret,
            certSha256 = link.certSha256 ?: _settings.value.certSha256,
        )
    }

    fun storeCertFingerprint(sha256Hex: String) {
        update(certSha256 = sha256Hex)
    }

    private fun load(): RelaySettings = RelaySettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        url = prefs.getString(KEY_URL, "").orEmpty(),
        secret = prefs.getString(KEY_SECRET, "").orEmpty(),
        certSha256 = prefs.getString(KEY_CERT, null)?.takeIf { it.isNotBlank() },
    )

    private companion object {
        const val PREFS = "voxcrew_relay"
        const val KEY_ENABLED = "enabled"
        const val KEY_URL = "url"
        const val KEY_SECRET = "secret"
        const val KEY_CERT = "cert_sha256"
    }
}
