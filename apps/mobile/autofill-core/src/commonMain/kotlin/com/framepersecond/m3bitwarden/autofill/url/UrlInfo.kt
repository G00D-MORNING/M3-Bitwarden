package com.framepersecond.m3bitwarden.autofill.url

/**
 * Parsed form of a login URI as Bitwarden stores it. Accepts full URLs, bare hosts
 * ("example.com"), hosts with ports and the app schemes `androidapp://` / `iosapp://`.
 */
data class UrlInfo(
    val raw: String,
    val scheme: String?,
    val host: String?,
    val port: Int?,
    val path: String,
    val androidPackage: String?,
    val iosBundleId: String?,
) {
    val isApp: Boolean get() = androidPackage != null || iosBundleId != null
    val isIp: Boolean get() = host != null && isIpAddress(host)
    val registrableDomain: String? by lazy { host?.let { PublicSuffixList.registrableDomain(it) } }

    /** "https://host:port" without a trailing slash, or null for app URIs. */
    val origin: String?
        get() = host?.let { "${scheme ?: "https"}://$it" + (port?.let { p -> ":$p" } ?: "") }

    companion object {
        private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

        fun parse(input: String?): UrlInfo? {
            val text = input?.trim().orEmpty()
            if (text.isEmpty()) return null
            val lower = text.lowercase()
            if (lower.startsWith("androidapp://")) {
                val pkg = text.substring("androidapp://".length).trim('/').takeIf { it.isNotEmpty() } ?: return null
                return UrlInfo(text, "androidapp", null, null, "", pkg, null)
            }
            if (lower.startsWith("iosapp://")) {
                val id = text.substring("iosapp://".length).trim('/').takeIf { it.isNotEmpty() } ?: return null
                return UrlInfo(text, "iosapp", null, null, "", null, id)
            }
            val schemeMatch = SCHEME.find(text)
            val scheme = schemeMatch?.groupValues?.get(1)?.lowercase()
            val rest = if (schemeMatch != null) text.substring(schemeMatch.range.last + 1) else text
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
            val path = rest.substring(authorityEnd)
            val (host, port) = splitHostPort(authority) ?: return null
            if (host.isEmpty() || host.any { it.isWhitespace() }) return null
            return UrlInfo(text, scheme, host.lowercase().trimEnd('.'), port, path, null, null)
        }

        private fun splitHostPort(authority: String): Pair<String, Int?>? {
            if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                if (end < 0) return null
                val host = authority.substring(1, end)
                val port = authority.substring(end + 1).removePrefix(":").toIntOrNull()
                return host to port
            }
            val colon = authority.lastIndexOf(':')
            return if (colon > 0 && authority.count { it == ':' } == 1) {
                authority.substring(0, colon) to authority.substring(colon + 1).toIntOrNull()
            } else {
                authority to null
            }
        }

        fun isIpAddress(host: String): Boolean {
            if (host.contains(':')) return true // IPv6
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }
    }
}
