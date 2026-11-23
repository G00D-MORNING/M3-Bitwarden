package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.autofill.match.Credential
import com.framepersecond.m3bitwarden.autofill.match.FillTarget
import com.framepersecond.m3bitwarden.autofill.match.LearnedLink
import com.framepersecond.m3bitwarden.autofill.match.LoginUri
import com.framepersecond.m3bitwarden.autofill.match.MatchEngine
import com.framepersecond.m3bitwarden.autofill.match.MatchReason
import com.framepersecond.m3bitwarden.autofill.match.UriMatch
import com.framepersecond.m3bitwarden.autofill.url.PublicSuffixList
import com.framepersecond.m3bitwarden.autofill.url.UrlInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchingTest {
    private fun login(id: String, vararg uris: String, match: UriMatch? = null, name: String = id) =
        Credential(id = id, name = name, username = "$id@example.com", uris = uris.map { LoginUri(it, match) })

    private val chrome = FillTarget(appId = "com.android.chrome", isTrustedBrowser = true)
    private fun page(domain: String, scheme: String = "https") = chrome.copy(webDomain = domain, webScheme = scheme)

    @Test
    fun publicSuffixList() {
        assertEquals("example.co.uk", PublicSuffixList.registrableDomain("login.example.co.uk"))
        assertEquals("alice.github.io", PublicSuffixList.registrableDomain("alice.github.io"))
        assertEquals("jochenderrochen.de", PublicSuffixList.registrableDomain("tresor.jochenderrochen.de"))
        assertNull(PublicSuffixList.registrableDomain("co.uk"))
        assertNull(PublicSuffixList.registrableDomain("192.168.178.1"))
    }

    @Test
    fun urlParsing() {
        val u = UrlInfo.parse("https://User@Login.Example.com:8443/path?q=1")!!
        assertEquals("login.example.com", u.host)
        assertEquals(8443, u.port)
        assertEquals("/path?q=1", u.path)
        assertEquals("com.netflix.mediaclient", UrlInfo.parse("androidapp://com.netflix.mediaclient")!!.androidPackage)
        assertEquals("example.com", UrlInfo.parse("example.com")!!.host)
    }

    @Test
    fun subdomainMatchesBaseDomain() {
        val r = MatchEngine().match(page("accounts.google.com"), listOf(login("g", "https://google.com"), login("x", "https://example.com")))
        assertEquals(listOf("g"), r.primary.map { it.credential.id })
        assertEquals(MatchReason.Domain, r.best?.reason)
    }

    @Test
    fun githubPagesAreIsolatedByThePublicSuffixList() {
        val r = MatchEngine().match(page("mallory.github.io"), listOf(login("a", "https://alice.github.io")))
        assertTrue(r.primary.isEmpty() && r.possible.isEmpty())
    }

    @Test
    fun hostModeRequiresTheExactHost() {
        val creds = listOf(login("h", "https://mail.example.com", match = UriMatch.Host))
        assertTrue(MatchEngine().match(page("www.example.com"), creds).primary.isEmpty())
        assertEquals(1, MatchEngine().match(page("mail.example.com"), creds).primary.size)
    }

    @Test
    fun neverIsNeverOffered() {
        assertTrue(MatchEngine().match(page("example.com"), listOf(login("n", "https://example.com", match = UriMatch.Never))).primary.isEmpty())
    }

    @Test
    fun equivalentDomains() {
        val engine = MatchEngine(equivalentDomains = listOf(setOf("amazon.com", "amazon.de", "amazon.co.uk")))
        val r = engine.match(page("www.amazon.de"), listOf(login("a", "https://www.amazon.com")))
        assertEquals(MatchReason.EquivalentDomain, r.best?.reason)
    }

    @Test
    fun httpDowngradeStillMatchesButIsFlagged() {
        val r = MatchEngine().match(page("example.com", scheme = "http"), listOf(login("e", "https://example.com")))
        val m = (r.primary + r.possible).single()
        assertTrue(m.insecureTransport)
    }

    @Test
    fun regexUris() {
        val creds = listOf(login("r", "^https://(eu|us)\\.service\\.com", match = UriMatch.RegularExpression))
        assertEquals(1, MatchEngine().match(page("eu.service.com"), creds).primary.size)
        assertTrue(MatchEngine().match(page("asia.service.com"), creds).primary.isEmpty())
    }

    @Test
    fun androidAppUri() {
        val target = FillTarget(appId = "com.netflix.mediaclient")
        val r = MatchEngine().match(target, listOf(login("n", "androidapp://com.netflix.mediaclient")))
        assertEquals(MatchReason.AppId, r.best?.reason)
        assertEquals(100, r.best?.strength)
    }

    @Test
    fun learnedLinkIsBoundToTheSigningCertificate() {
        val links = listOf(LearnedLink("db", appId = "de.db.navigator", appCertSha256 = "AA11"))
        val creds = listOf(login("db", "https://bahn.de"))
        val genuine = MatchEngine(learnedLinks = links).match(FillTarget(appId = "de.db.navigator", appCertSha256 = "aa11"), creds)
        assertEquals(MatchReason.LearnedLink, genuine.best?.reason)
        val impostor = MatchEngine(learnedLinks = links).match(FillTarget(appId = "de.db.navigator", appCertSha256 = "FF00"), creds)
        assertTrue(impostor.primary.none { it.reason == MatchReason.LearnedLink })
    }

    @Test
    fun webViewInAnOrdinaryAppNeedsConfirmation() {
        val target = FillTarget(appId = "com.some.game", webDomain = "accounts.google.com")
        val r = MatchEngine().match(target, listOf(login("g", "https://google.com")))
        assertTrue(r.primary.isEmpty())
        assertTrue(r.possible.single().requiresConfirmation)
    }

    @Test
    fun webViewOfAVerifiedAppIsFilledDirectly() {
        val engine = MatchEngine(appDomains = mapOf("com.google.android.gm" to setOf("google.com")))
        val target = FillTarget(appId = "com.google.android.gm", webDomain = "accounts.google.com")
        assertEquals(1, engine.match(target, listOf(login("g", "https://google.com"))).primary.size)
    }

    @Test
    fun appVerifiedDomainMatchesNativeApps() {
        val engine = MatchEngine(appDomains = mapOf("com.paypal.android.p2pmobile" to setOf("paypal.com")))
        val r = engine.match(FillTarget(appId = "com.paypal.android.p2pmobile"), listOf(login("p", "https://www.paypal.com")))
        assertEquals(MatchReason.AppVerifiedDomain, r.best?.reason)
    }

    @Test
    fun nameHeuristicOnlySuggests() {
        val r = MatchEngine().match(
            FillTarget(appId = "com.netflix.mediaclient", appLabel = "Netflix"),
            listOf(login("n", "https://www.netflix.com", name = "Netflix"), login("s", "https://spotify.com", name = "Spotify")),
        )
        assertTrue(r.primary.isEmpty())
        val m = r.possible.single()
        assertEquals("n", m.credential.id)
        assertTrue(m.requiresConfirmation)
    }

    @Test
    fun recentlyUsedItemsRankFirst() {
        val day = 86_400_000L
        val now = 100 * day
        val creds = listOf(
            login("old", "https://example.com").copy(lastUsedEpochMs = now - 60 * day, useCount = 3),
            login("new", "https://example.com").copy(lastUsedEpochMs = now - day, useCount = 1),
        )
        val r = MatchEngine(nowEpochMs = { now }).match(page("example.com"), creds)
        assertEquals(listOf("new", "old"), r.primary.map { it.credential.id })
    }

    @Test
    fun ipAddressesMatchOnlyThemselves() {
        val creds = listOf(login("nas", "http://192.168.178.20:5000"))
        assertEquals(1, MatchEngine().match(page("192.168.178.20", scheme = "http"), creds).primary.size)
        assertFalse(MatchEngine().match(page("192.168.178.21", scheme = "http"), creds).primary.isNotEmpty())
    }
}
