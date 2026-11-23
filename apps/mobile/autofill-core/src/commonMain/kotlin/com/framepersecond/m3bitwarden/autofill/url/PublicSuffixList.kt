package com.framepersecond.m3bitwarden.autofill.url

/**
 * Registrable-domain lookup ("eTLD+1") based on the Public Suffix List.
 *
 * This is what keeps `alice.github.io` apart from `mallory.github.io` and lets
 * `login.example.co.uk` match `example.co.uk`, where naive "last two labels" logic fails.
 */
object PublicSuffixList {

    private class Rules(val exact: Set<String>, val wildcard: Set<String>, val exception: Set<String>)

    private val rules: Rules by lazy {
        val exact = HashSet<String>(PslData.RULE_COUNT * 2)
        val wildcard = HashSet<String>()
        val exception = HashSet<String>()
        for (chunk in PslData.chunks()) {
            for (line in chunk.lineSequence()) {
                val rule = line.trim()
                when {
                    rule.isEmpty() -> Unit
                    rule.startsWith("!") -> exception += rule.substring(1)
                    rule.startsWith("*.") -> wildcard += rule.substring(2)
                    else -> exact += rule
                }
            }
        }
        Rules(exact, wildcard, exception)
    }

    /** The public suffix of [host], for example "co.uk" for "a.b.co.uk". */
    fun publicSuffix(host: String): String {
        val labels = host.trimEnd('.').lowercase().split('.')
        val r = rules
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (candidate in r.exception) {
                // Exception rules mean "the suffix is one label shorter than this".
                return labels.subList(i + 1, labels.size).joinToString(".")
            }
            if (candidate in r.exact) return candidate
            if (i + 1 < labels.size) {
                val parent = labels.subList(i + 1, labels.size).joinToString(".")
                if (parent in r.wildcard) return candidate
            }
        }
        // Default rule "*": the last label is the suffix.
        return labels.last()
    }

    /** The registrable domain, or null when [host] is itself a public suffix or an IP address. */
    fun registrableDomain(host: String): String? {
        val clean = host.trimEnd('.').lowercase()
        if (clean.isEmpty() || UrlInfo.isIpAddress(clean)) return null
        val suffix = publicSuffix(clean)
        if (clean == suffix) return null
        val prefix = clean.removeSuffix(".$suffix")
        val lastLabel = prefix.substringAfterLast('.')
        return "$lastLabel.$suffix"
    }
}
