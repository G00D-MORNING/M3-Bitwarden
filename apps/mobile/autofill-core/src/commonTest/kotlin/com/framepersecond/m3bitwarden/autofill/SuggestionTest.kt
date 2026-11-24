package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.match.Credential
import com.framepersecond.m3bitwarden.autofill.match.FillTarget
import com.framepersecond.m3bitwarden.autofill.match.LoginUri
import com.framepersecond.m3bitwarden.autofill.match.MatchEngine
import com.framepersecond.m3bitwarden.autofill.suggest.InstalledApp
import com.framepersecond.m3bitwarden.autofill.suggest.LinkReason
import com.framepersecond.m3bitwarden.autofill.suggest.SaveDecision
import com.framepersecond.m3bitwarden.autofill.suggest.Submitted
import com.framepersecond.m3bitwarden.autofill.suggest.Suggester
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestionTest {
    private val netflix = Credential("n", "Netflix", username = "du@example.com", uris = listOf(LoginUri("https://www.netflix.com")))
    private val paypal = Credential("p", "PayPal", username = "du@example.com", uris = listOf(LoginUri("https://paypal.com")))
    private val vault = listOf(netflix, paypal)
    private val passwords = mapOf("n" to "geheim1", "p" to "geheim2")
    private val eq: (String, String) -> Boolean = { id, pw -> passwords[id] == pw }

    @Test
    fun manualPickInAnUnlinkedAppOffersTheAppLink() {
        val target = FillTarget(appId = "com.netflix.mediaclient", appLabel = "Netflix")
        val result = MatchEngine().match(target, vault)
        val s = Suggester().afterFill(netflix, target, result)!!
        assertEquals("androidapp://com.netflix.mediaclient", s.uriToAdd)
        assertEquals(LinkReason.ManualPick, s.reason)
    }

    @Test
    fun noOfferWhenTheMatchWasAlreadySolid() {
        val target = FillTarget(appId = "com.android.chrome", isTrustedBrowser = true, webDomain = "www.netflix.com")
        assertNull(Suggester().afterFill(netflix, target, MatchEngine().match(target, vault)))
    }

    @Test
    fun browserPickOffersTheWebsite() {
        val target = FillTarget(appId = "com.android.chrome", isTrustedBrowser = true, webDomain = "netflix.de")
        val s = Suggester().afterFill(netflix, target, MatchEngine().match(target, vault))!!
        assertEquals("https://netflix.de", s.uriToAdd)
    }

    @Test
    fun dismissedSuggestionsStayQuiet() {
        val target = FillTarget(appId = "com.netflix.mediaclient")
        val first = Suggester().afterFill(netflix, target, MatchEngine().match(target, vault))!!
        assertNull(Suggester(dismissed = setOf(first.key)).afterFill(netflix, target, MatchEngine().match(target, vault)))
    }

    @Test
    fun vaultReviewPrefersVerifiedApps() {
        val apps = listOf(
            InstalledApp("com.paypal.android.p2pmobile", "PayPal", verifiedDomains = setOf("paypal.com")),
            InstalledApp("com.netflix.mediaclient", "Netflix"),
            InstalledApp("com.whatsapp", "WhatsApp"),
        )
        val list = Suggester().reviewVault(vault, apps)
        assertEquals(listOf("p", "n"), list.map { it.credential.id })
        assertEquals(LinkReason.VerifiedAppDomain, list.first().reason)
    }

    @Test
    fun alreadyLinkedAppsAreSkippedInReview() {
        val linked = netflix.copy(uris = netflix.uris + LoginUri("androidapp://com.netflix.mediaclient"))
        assertTrue(Suggester().reviewVault(listOf(linked), listOf(InstalledApp("com.netflix.mediaclient", "Netflix"))).isEmpty())
    }

    @Test
    fun saveAndUpdateDecisions() {
        val target = FillTarget(appId = "com.android.chrome", isTrustedBrowser = true, webDomain = "www.netflix.com")
        val result = MatchEngine().match(target, vault)
        val s = Suggester()
        assertEquals(SaveDecision.Nothing, s.afterSubmit(Submitted("du@example.com", "geheim1", FormIntent.Login), target, result, vault, eq))
        assertIs<SaveDecision.UpdatePassword>(s.afterSubmit(Submitted("du@example.com", "neu!", FormIntent.Login), target, result, vault, eq))
        assertIs<SaveDecision.SaveNew>(s.afterSubmit(Submitted("zweit@example.com", "x", FormIntent.Login), target, result, vault, eq))
    }

    @Test
    fun reusedCredentialsAreLinkedInsteadOfDuplicated() {
        val target = FillTarget(appId = "com.android.chrome", isTrustedBrowser = true, webDomain = "paypal.de")
        val result = MatchEngine().match(target, vault)
        val d = Suggester().afterSubmit(Submitted("du@example.com", "geheim2", FormIntent.Login), target, result, vault, eq)
        assertIs<SaveDecision.LinkExisting>(d)
        assertEquals("p", d.suggestion.credential.id)
        assertEquals("https://paypal.de", d.suggestion.uriToAdd)
    }

    @Test
    fun passwordStepUpdatesTheItemFromStepOne() {
        val target = FillTarget(appId = "com.android.chrome", isTrustedBrowser = true, webDomain = "www.netflix.com")
        val result = MatchEngine().match(target, vault)
        val d = Suggester().afterSubmit(Submitted(null, "anders", FormIntent.PasswordStep), target, result, vault, eq)
        assertIs<SaveDecision.UpdatePassword>(d)
    }
}

class KnownAppTest {
    private fun login(id: String, vararg uris: String) =
        com.framepersecond.m3bitwarden.autofill.match.Credential(id = id, name = id, username = "u", uris = uris.map { com.framepersecond.m3bitwarden.autofill.match.LoginUri(it) })

    @kotlin.test.Test
    fun knownAppIsSuggestedButNeverFilledSilently() {
        val engine = com.framepersecond.m3bitwarden.autofill.match.MatchEngine()
        val target = com.framepersecond.m3bitwarden.autofill.match.FillTarget(appId = "de.hafas.android.db", appLabel = "DB Navigator")
        val result = engine.match(target, listOf(login("bahn", "https://www.bahn.de"), login("other", "https://example.com")))
        kotlin.test.assertTrue(result.primary.isEmpty())
        kotlin.test.assertEquals(listOf("bahn"), result.possible.map { it.credential.id })
        kotlin.test.assertEquals(com.framepersecond.m3bitwarden.autofill.match.MatchReason.KnownApp, result.possible.first().reason)
        val suggestions = com.framepersecond.m3bitwarden.autofill.suggest.Suggester().forTarget(target, result)
        kotlin.test.assertEquals("androidapp://de.hafas.android.db", suggestions.first().uriToAdd)
    }

    @kotlin.test.Test
    fun reverseDomainPackageNames() {
        kotlin.test.assertEquals("dkb.de", com.framepersecond.m3bitwarden.autofill.match.KnownApps.reverseDomainGuess("de.dkb.portalapp"))
        kotlin.test.assertEquals("paypal.com", com.framepersecond.m3bitwarden.autofill.match.KnownApps.reverseDomainGuess("com.paypal.android.p2pmobile"))
        kotlin.test.assertEquals(null, com.framepersecond.m3bitwarden.autofill.match.KnownApps.reverseDomainGuess("com.google.android.apps.maps"))
        val engine = com.framepersecond.m3bitwarden.autofill.match.MatchEngine()
        val target = com.framepersecond.m3bitwarden.autofill.match.FillTarget(appId = "de.meinestadtwerke.app")
        val result = engine.match(target, listOf(login("sw", "https://kundenportal.meinestadtwerke.de")))
        kotlin.test.assertEquals(listOf("sw"), result.possible.map { it.credential.id })
    }
}
