package com.framepersecond.m3bitwarden.autofill.suggest

import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.match.Credential
import com.framepersecond.m3bitwarden.autofill.match.AppPlatform
import com.framepersecond.m3bitwarden.autofill.match.CredentialKind
import com.framepersecond.m3bitwarden.autofill.match.FillTarget
import com.framepersecond.m3bitwarden.autofill.match.Match
import com.framepersecond.m3bitwarden.autofill.match.MatchEngine
import com.framepersecond.m3bitwarden.autofill.match.MatchReason
import com.framepersecond.m3bitwarden.autofill.match.MatchResult
import com.framepersecond.m3bitwarden.autofill.url.UrlInfo

enum class LinkReason {
    /** The user picked this item by hand for an app or site it was not linked to. */
    ManualPick,

    /** The app is officially tied to a domain the item already has (Digital Asset Links). */
    VerifiedAppDomain,

    /** App name and item name look alike. Lowest confidence, always asked. */
    NameMatch,
}

/**
 * "Use this login for <app/site> from now on?". Accepting it adds [uriToAdd] to the item in
 * Bitwarden (synced to every device) and records a local [com.framepersecond.m3bitwarden.autofill.match.LearnedLink].
 */
data class LinkSuggestion(
    val credential: Credential,
    val uriToAdd: String,
    val appId: String?,
    val appLabel: String?,
    val domain: String?,
    val reason: LinkReason,
    val confidence: Int,
) {
    /** Stable key for "don't ask again". */
    val key: String get() = "${credential.id}|${appId ?: domain}"
}

data class InstalledApp(
    val appId: String,
    val label: String,
    val verifiedDomains: Set<String> = emptySet(),
)

sealed interface SaveDecision {
    data object Nothing : SaveDecision
    data class SaveNew(val username: String, val uri: String?) : SaveDecision
    data class UpdatePassword(val credential: Credential) : SaveDecision

    /** Same username and password already exist under another site: link instead of duplicating. */
    data class LinkExisting(val suggestion: LinkSuggestion) : SaveDecision
}

/** What was typed into the form when it was submitted. Secrets are only compared, never stored here. */
data class Submitted(
    val username: String?,
    val password: String?,
    val intent: FormIntent,
)

/**
 * The proactive layer Proton Pass is known for, made more systematic:
 * link offers after a manual pick, verified app ↔ site pairs, a periodic vault review
 * for installed apps, and save / update prompts that avoid duplicates.
 */
class Suggester(
    private val dismissed: Set<String> = emptySet(),
) {

    /** After the user filled [picked] on [target]: offer a link if it was not already a strong match. */
    fun afterFill(picked: Credential, target: FillTarget, result: MatchResult): LinkSuggestion? {
        val match = (result.primary + result.possible).firstOrNull { it.credential.id == picked.id }
        if (match != null && match.isSolid()) return null
        return linkFor(picked, target, LinkReason.ManualPick, confidence = 90)?.takeUnless { it.key in dismissed }
    }

    /** While a fill request is open: suggestions worth showing next to the matches. */
    fun forTarget(target: FillTarget, result: MatchResult): List<LinkSuggestion> {
        val out = ArrayList<LinkSuggestion>()
        // Matched only through the app's verified domain: add the app URI so the next
        // match is instant and also works offline and on other devices.
        result.primary.filter { it.reason == MatchReason.AppVerifiedDomain }.forEach { m ->
            linkFor(m.credential, target, LinkReason.VerifiedAppDomain, confidence = 85)?.let(out::add)
        }
        result.possible.filter { it.reason == MatchReason.NameHeuristic }.take(2).forEach { m ->
            linkFor(m.credential, target, LinkReason.NameMatch, confidence = m.strength)?.let(out::add)
        }
        return out.filter { it.key !in dismissed }.distinctBy { it.key }
    }

    /**
     * Periodic review ("Proton schlägt regelmäßig vor …"): for every installed app that has no
     * linked login, propose the most likely one. Verified pairs come first.
     */
    fun reviewVault(credentials: List<Credential>, apps: List<InstalledApp>, limit: Int = 20): List<LinkSuggestion> {
        val logins = credentials.filter { it.kind == CredentialKind.Login }
        val linkedApps = logins.flatMap { c -> c.uris.mapNotNull { UrlInfo.parse(it.uri)?.androidPackage ?: UrlInfo.parse(it.uri)?.iosBundleId } }.toSet()
        val out = ArrayList<LinkSuggestion>()
        for (app in apps) {
            if (app.appId in linkedApps) continue
            val target = FillTarget(appId = app.appId, appLabel = app.label)
            val engine = MatchEngine(appDomains = mapOf(app.appId to app.verifiedDomains))
            val result = engine.match(target, logins)
            val verified = result.primary.firstOrNull { it.reason == MatchReason.AppVerifiedDomain }
            val suggestion = when {
                verified != null -> linkFor(verified.credential, target, LinkReason.VerifiedAppDomain, 85)
                else -> result.possible.firstOrNull { it.reason == MatchReason.NameHeuristic && it.strength >= 55 }
                    ?.let { linkFor(it.credential, target, LinkReason.NameMatch, it.strength) }
            }
            if (suggestion != null && suggestion.key !in dismissed) out += suggestion
        }
        return out.sortedByDescending { it.confidence }.take(limit)
    }

    /**
     * After a form was submitted. [passwordEquals] compares against the vault without handing
     * the stored secret to this layer.
     */
    fun afterSubmit(
        submitted: Submitted,
        target: FillTarget,
        result: MatchResult,
        allCredentials: List<Credential>,
        passwordEquals: (credentialId: String, password: String) -> Boolean,
    ): SaveDecision {
        val password = submitted.password?.takeIf { it.isNotEmpty() } ?: return SaveDecision.Nothing
        val username = submitted.username?.trim().orEmpty()
        val matched = result.primary.map { it.credential }
        val sameUser = matched.firstOrNull { username.isNotEmpty() && it.username.equals(username, ignoreCase = true) }

        return when (submitted.intent) {
            FormIntent.ChangePassword -> {
                val c = sameUser ?: matched.singleOrNull()
                if (c == null || passwordEquals(c.id, password)) SaveDecision.Nothing else SaveDecision.UpdatePassword(c)
            }
            else -> when {
                sameUser != null && passwordEquals(sameUser.id, password) -> SaveDecision.Nothing
                sameUser != null -> SaveDecision.UpdatePassword(sameUser)
                // Password-only second step: the item was matched in step one.
                username.isEmpty() && matched.size == 1 ->
                    if (passwordEquals(matched[0].id, password)) SaveDecision.Nothing else SaveDecision.UpdatePassword(matched[0])
                else -> {
                    val reused = allCredentials.firstOrNull {
                        it.kind == CredentialKind.Login && username.isNotEmpty() &&
                            it.username.equals(username, ignoreCase = true) && passwordEquals(it.id, password)
                    }
                    val link = reused?.let { linkFor(it, target, LinkReason.ManualPick, 80) }
                    if (link != null) SaveDecision.LinkExisting(link) else SaveDecision.SaveNew(username, uriFor(target))
                }
            }
        }
    }

    private fun Match.isSolid(): Boolean = !requiresConfirmation && strength >= 85

    private fun linkFor(c: Credential, target: FillTarget, reason: LinkReason, confidence: Int): LinkSuggestion? {
        val uri = uriFor(target) ?: return null
        if (c.uris.any { it.uri.equals(uri, ignoreCase = true) }) return null
        val domain = target.webDomain?.let { UrlInfo.parse("https://$it")?.registrableDomain ?: it }
        return LinkSuggestion(c, uri, target.appId.takeIf { domain == null }, target.appLabel, domain, reason, confidence)
    }

    /** Website URI for browsers and trusted pages, otherwise the app URI Bitwarden understands. */
    private fun uriFor(target: FillTarget): String? = when {
        target.webDomain != null && (target.isTrustedBrowser || target.appId == null) -> "${target.webScheme ?: "https"}://${target.webDomain}"
        target.appId != null && target.appId.contains('.') && !target.appId.contains(' ') ->
            if (target.platform == AppPlatform.Ios) "iosapp://${target.appId}" else "androidapp://${target.appId}"
        target.webDomain != null -> "${target.webScheme ?: "https"}://${target.webDomain}"
        else -> null
    }
}
