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
        if (!scheme.equals(SCHEME, ignoreCase = true)) return null
        val rest = withoutFragment.substring(schemeSep + 3)
        val pathAndQuery = rest.substringBefore('?')
        val host = pathAndQuery.substringBefore('/').lowercase()
        val path = pathAndQuery.substringAfter('/', missingDelimiterValue = "").trim('/')
        if (host != HOST && path != HOST && host.isNotEmpty()) {
            // voxcrew://relay-config?... → host is relay-config
            return null
        }
        if (host != HOST && path != HOST) return null

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
        val parts = mutableListOf(
            "url=${encode(url)}",
            "secret=${encode(secret)}",
        )
        if (!certSha256.isNullOrBlank()) {
            parts += "certSha256=${encode(certSha256.lowercase())}"
        }
        return "$SCHEME://$HOST?${parts.joinToString("&")}"
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
