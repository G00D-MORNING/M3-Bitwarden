package com.framepersecond.m3bitwarden.autofill.match

import com.framepersecond.m3bitwarden.autofill.text.TextNorm
import com.framepersecond.m3bitwarden.autofill.url.PublicSuffixList
import com.framepersecond.m3bitwarden.autofill.url.UrlInfo

/**
 * Decides which vault items belong to the app or page asking for autofill.
 *
 * Compared with Proton Pass this adds:
 * - Bitwarden's per-URI match modes (base domain, host, starts with, exact, regex, never),
 * - Bitwarden's equivalent domains (amazon.de ↔ amazon.com, google.com ↔ youtube.com),
 * - learned links from earlier manual picks, bound to the app's signing certificate,
 * - verified app ↔ website pairs from Digital Asset Links,
 * - https → http downgrade detection instead of a hard scheme mismatch,
 * - web views inside non-browser apps are never filled silently (phishing guard),
 * - a name heuristic ("com.netflix.mediaclient" ↔ "Netflix") that only ever suggests.
 */
class MatchEngine(
    /** Groups of domains Bitwarden treats as the same site, from `/api/settings/domains`. */
    equivalentDomains: List<Set<String>> = emptyList(),
    /** Verified website domains per app id (Digital Asset Links / Associated Domains). */
    private val appDomains: Map<String, Set<String>> = emptyMap(),
    private val learnedLinks: List<LearnedLink> = emptyList(),
    /** The user's default match mode for URIs without an explicit one. */
    private val defaultMatch: UriMatch = UriMatch.Domain,
    private val nowEpochMs: () -> Long = { 0L },
) {
    private val equivalents: Map<String, Set<String>> = buildMap {
        for (group in equivalentDomains) {
            val normalized = group.map { it.lowercase() }.toSet()
            for (d in normalized) put(d, (get(d) ?: emptySet()) + normalized)
        }
    }

    fun match(target: FillTarget, credentials: List<Credential>): MatchResult {
        val webUrl = target.url?.let(UrlInfo::parse)
            ?: target.webDomain?.let { UrlInfo.parse("${target.webScheme ?: "https"}://$it") }
        val verifiedForApp = target.appId?.let { appDomains[it] }.orEmpty().map { it.lowercase() }.toSet()
        // A web view inside an ordinary app may show any page. Unless the app is verified
        // for that domain, matches found through the page domain need a confirmation.
        val webViewUnverified = webUrl != null && target.appId != null && !target.isTrustedBrowser &&
            webUrl.registrableDomain.let { reg -> reg == null || reg !in verifiedForApp.map { PublicSuffixList.registrableDomain(it) ?: it } }

        val all = credentials.mapNotNull { cred ->
            val candidates = buildList {
                addAll(matchApp(cred, target))
                if (webUrl != null) addAll(matchWeb(cred, webUrl, target))
                if (webUrl == null && target.appId != null) addAll(matchViaAppDomains(cred, verifiedForApp))
            }
            val best = candidates.maxByOrNull { it.strength } ?: return@mapNotNull null
            val needsConfirm = best.requiresConfirmation ||
                (webViewUnverified && best.reason != MatchReason.AppId && best.reason != MatchReason.LearnedLink)
            best.copy(requiresConfirmation = needsConfirm)
        }

        val heuristic = if (all.none { it.strength >= PRIMARY_THRESHOLD } && target.appId != null && webUrl == null) {
            nameHeuristic(target, credentials, alreadyMatched = all.map { it.credential.id }.toSet())
        } else {
            emptyList()
        }

        val ranked = (all + heuristic).sortedWith(ranking())
        val (primary, possible) = ranked.partition { it.strength >= PRIMARY_THRESHOLD && !it.requiresConfirmation }
        return MatchResult(primary, possible)
    }

    private fun matchApp(cred: Credential, target: FillTarget): List<Match> {
        val appId = target.appId ?: return emptyList()
        val out = ArrayList<Match>()
        for (u in cred.uris) {
            if (u.match == UriMatch.Never) continue
            val info = UrlInfo.parse(u.uri) ?: continue
            if (info.androidPackage == appId || info.iosBundleId == appId) out += Match(cred, 100, MatchReason.AppId)
        }
        for (link in learnedLinks) {
            if (link.credentialId != cred.id || link.appId != appId) continue
            // A learned link is bound to the certificate seen when it was made: a different
            // app reusing the package name must not inherit it.
            val certOk = link.appCertSha256 == null || target.appCertSha256 == null || link.appCertSha256.equals(target.appCertSha256, ignoreCase = true)
            if (certOk) out += Match(cred, 98, MatchReason.LearnedLink)
        }
        return out
    }

    private fun matchWeb(cred: Credential, page: UrlInfo, target: FillTarget): List<Match> {
        val pageHost = page.host ?: return emptyList()
        val out = ArrayList<Match>()
        for (u in cred.uris) {
            val mode = u.match ?: defaultMatch
            if (mode == UriMatch.Never) continue
            val stored = UrlInfo.parse(u.uri) ?: continue
            if (stored.isApp) continue
            val storedHost = stored.host ?: continue
            val downgrade = page.scheme == "http" && stored.scheme == "https"
            fun m(strength: Int, reason: MatchReason) =
                Match(cred, if (downgrade) strength - 25 else strength, reason, insecureTransport = downgrade)

            when (mode) {
                UriMatch.Exact -> {
                    val full = target.url ?: continue
                    if (full.trimEnd('/') == u.uri.trim().trimEnd('/')) out += m(100, MatchReason.ExactUrl)
                }
                UriMatch.StartsWith -> {
                    val full = target.url
                    when {
                        full != null && full.startsWith(u.uri.trim()) -> out += m(96, MatchReason.StartsWith)
                        // Android only reports the domain: accept origin-only URIs, else only suggest.
                        full == null && storedHost == pageHost && stored.port == page.port ->
                            out += if (stored.path.isEmpty() || stored.path == "/") m(90, MatchReason.StartsWith) else m(50, MatchReason.StartsWith)
                    }
                }
                UriMatch.RegularExpression -> {
                    val subject = target.url ?: page.origin ?: continue
                    val regex = runCatching { Regex(u.uri, RegexOption.IGNORE_CASE) }.getOrNull() ?: continue
                    if (regex.containsMatchIn(subject)) out += m(92, MatchReason.Regex)
                }
                UriMatch.Host -> if (storedHost == pageHost && (stored.port == null || stored.port == page.port)) out += m(94, MatchReason.Host)
                UriMatch.Domain -> {
                    when {
                        storedHost == pageHost -> out += m(94, MatchReason.Host)
                        page.isIp || stored.isIp -> Unit // IPs only ever match exactly
                        stored.registrableDomain != null && stored.registrableDomain == page.registrableDomain -> out += m(86, MatchReason.Domain)
                        stored.registrableDomain != null && page.registrableDomain != null &&
                            equivalents[stored.registrableDomain]?.contains(page.registrableDomain) == true ->
                            out += m(72, MatchReason.EquivalentDomain)
                    }
                }
                UriMatch.Never -> Unit
            }
        }
        for (link in learnedLinks) {
            if (link.credentialId == cred.id && link.domain != null && link.domain.equals(page.registrableDomain ?: pageHost, ignoreCase = true)) {
                out += Match(cred, 97, MatchReason.LearnedLink)
            }
        }
        return out
    }

    private fun matchViaAppDomains(cred: Credential, verified: Set<String>): List<Match> {
        if (verified.isEmpty()) return emptyList()
        val verifiedRegistrable = verified.mapNotNull { PublicSuffixList.registrableDomain(it) ?: it }.toSet()
        return cred.uris.mapNotNull { u ->
            if (u.match == UriMatch.Never) return@mapNotNull null
            val info = UrlInfo.parse(u.uri) ?: return@mapNotNull null
            val reg = info.registrableDomain ?: return@mapNotNull null
            if (reg in verifiedRegistrable || equivalents[reg].orEmpty().any { it in verifiedRegistrable }) {
                Match(cred, 88, MatchReason.AppVerifiedDomain)
            } else {
                null
            }
        }
    }

    /**
     * Last resort for apps without any link: compare the app's package/label with the
     * item's name and domain. Never filled automatically; it feeds link suggestions.
     */
    private fun nameHeuristic(target: FillTarget, credentials: List<Credential>, alreadyMatched: Set<String>): List<Match> {
        val appTokens = appTokens(target.appId, target.appLabel)
        if (appTokens.isEmpty()) return emptyList()
        return credentials.filter { it.id !in alreadyMatched && it.kind == CredentialKind.Login }.mapNotNull { cred ->
            val credTokens = buildSet {
                addAll(TextNorm.words(cred.name).filter { it.length >= 3 })
                cred.uris.mapNotNull { UrlInfo.parse(it.uri)?.registrableDomain?.substringBefore('.') }.forEach { add(TextNorm.squash(it)) }
            }
            val score = credTokens.maxOfOrNull { ct -> appTokens.maxOf { at -> similarity(at, ct) } } ?: 0
            if (score >= 70) Match(cred, 40 + (score - 70), MatchReason.NameHeuristic, requiresConfirmation = true) else null
        }
    }

    private fun ranking(): Comparator<Match> = compareByDescending<Match> { it.strength }
        .thenByDescending { frecency(it.credential) }
        .thenByDescending { it.credential.favorite }
        .thenBy { it.credential.name.lowercase() }

    /** Recently and frequently used items first; one use loses half its weight every 14 days. */
    private fun frecency(c: Credential): Double {
        val last = c.lastUsedEpochMs ?: return c.useCount.toDouble() * 0.01
        val ageDays = ((nowEpochMs() - last).coerceAtLeast(0)) / 86_400_000.0
        return (1 + c.useCount) * kotlin.math.exp(-ageDays * kotlin.math.ln(2.0) / 14.0)
    }

    companion object {
        const val PRIMARY_THRESHOLD = 60

        private val GENERIC_APP_TOKENS = setOf(
            "com", "org", "net", "de", "at", "ch", "co", "uk", "io", "app", "apps", "android", "ios", "mobile", "client",
            "mediaclient", "mobileclient", "prod", "release", "free", "pro", "lite", "the", "official", "main", "beta",
        )

        internal fun appTokens(appId: String?, appLabel: String?): Set<String> = buildSet {
            appId?.split('.')?.map(TextNorm::squash)?.filter { it.length >= 3 && it !in GENERIC_APP_TOKENS }?.forEach(::add)
            TextNorm.words(appLabel).filter { it.length >= 3 && it !in GENERIC_APP_TOKENS }.forEach(::add)
            TextNorm.squash(appLabel).takeIf { it.length >= 3 }?.let(::add)
        }

        /** 0–100. Equal → 100, one a prefix of the other (min 4 chars) → 80, else edit-distance based. */
        internal fun similarity(a: String, b: String): Int {
            if (a.isEmpty() || b.isEmpty()) return 0
            if (a == b) return 100
            val (short, long) = if (a.length <= b.length) a to b else b to a
            if (short.length >= 4 && long.startsWith(short)) return 80
            val d = levenshtein(a, b)
            val maxLen = maxOf(a.length, b.length)
            return ((1.0 - d.toDouble() / maxLen) * 100).toInt().let { if (maxLen < 5) it - 20 else it }
        }

        private fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }
    }
}
