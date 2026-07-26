package com.nblaisot.voxcrew.relay

/**
 * Deep link: `voxcrew://relay-config?url=...&secret=...&certSha256=...`
 *
 * Parsing is pure-JVM (no Android Uri) so unit tests do not need Robolectric.
 */
data class RelayConfigLink(
    val url: String,
    val secret: String,
    val certSha256: String? = null,
)

object RelayConfigLinkParser {
    const val SCHEME = "voxcrew"
    const val HOST = "relay-config"

    fun parse(uriString: String?): RelayConfigLink? {
        val raw = uriString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutFragment = raw.substringBefore('#')
        val schemeSep = withoutFragment.indexOf("://")
        if (schemeSep < 0) return null
        val scheme = withoutFragment.substring(0, schemeSep)

        // App deep link, or https invite page query (same params).
        val isAppDeepLink = scheme.equals(SCHEME, ignoreCase = true)
        val isHttpsInvite = scheme.equals("https", ignoreCase = true) ||
            scheme.equals("http", ignoreCase = true)
        if (!isAppDeepLink && !isHttpsInvite) return null

        if (isAppDeepLink) {
            val rest = withoutFragment.substring(schemeSep + 3)
            val pathAndQuery = rest.substringBefore('?')
            val host = pathAndQuery.substringBefore('/').lowercase()
            val path = pathAndQuery.substringAfter('/', missingDelimiterValue = "").trim('/')
            if (host != HOST && path != HOST) return null
        } else {
            val rest = withoutFragment.substring(schemeSep + 3)
            val pathAndQuery = rest.substringBefore('?')
            val path = pathAndQuery.substringAfter('/', missingDelimiterValue = "").trim('/').lowercase()
            if (path != "invite" && path != HOST) return null
        }

        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        val params = parseQuery(query)
        val url = params["url"]?.trim().orEmpty()
        val secret = params["secret"]?.trim().orEmpty()
        if (url.isEmpty() || secret.isEmpty()) return null
        val cert = params["certSha256"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return RelayConfigLink(url = url, secret = secret, certSha256 = cert)
    }

    fun build(url: String, secret: String, certSha256: String? = null): String {
        val parts = queryParts(url, secret, certSha256)
        return "$SCHEME://$HOST?${parts.joinToString("&")}"
    }

    /**
     * WhatsApp / Chrome only auto-link https. Share this; the relay serves a page
     * that opens [build] (`voxcrew://…`).
     */
    fun buildHttpsInvite(url: String, secret: String, certSha256: String? = null): String? {
        val httpBase = wssToHttpOrigin(url) ?: return null
        val parts = queryParts(url, secret, certSha256)
        return "$httpBase/invite?${parts.joinToString("&")}"
    }

    fun buildShareText(url: String, secret: String, certSha256: String? = null): String {
        val deep = build(url, secret, certSha256)
        val https = buildHttpsInvite(url, secret, certSha256)
        return if (https != null) {
            "VoxCrew relay invite (open in Chrome, then tap Open in VoxCrew):\n$https\n\n" +
                "Or paste in VoxCrew → Menu → Relay:\n$deep"
        } else {
            "Paste in VoxCrew → Menu → Relay:\n$deep"
        }
    }

    private fun wssToHttpOrigin(url: String): String? {
        val trimmed = url.trim()
        val origin = when {
            trimmed.startsWith("wss://", ignoreCase = true) ->
                "https://" + trimmed.substring(6)
            trimmed.startsWith("ws://", ignoreCase = true) ->
                "http://" + trimmed.substring(5)
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            else -> return null
        }
        // Keep host[:port] only — drop any path on the WSS URL.
        val afterScheme = origin.substringAfter("://")
        val hostPort = afterScheme.substringBefore('/').substringBefore('?')
        if (hostPort.isBlank()) return null
        val scheme = origin.substringBefore("://")
        return "$scheme://$hostPort"
    }

    private fun queryParts(url: String, secret: String, certSha256: String?): List<String> {
        val parts = mutableListOf(
            "url=${encode(url)}",
            "secret=${encode(secret)}",
        )
        if (!certSha256.isNullOrBlank()) {
            parts += "certSha256=${encode(certSha256.lowercase())}"
        }
        return parts
    }

    private fun parseQuery(query: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            val value = if (eq < 0) "" else part.substring(eq + 1)
            out[decode(key)] = decode(value)
        }
        return out
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String =
        java.net.URLDecoder.decode(value.replace("+", "%20"), Charsets.UTF_8.name())
}
