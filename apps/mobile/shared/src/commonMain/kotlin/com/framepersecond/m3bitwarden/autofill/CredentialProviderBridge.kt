package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.autofill.match.AppPlatform
import com.framepersecond.m3bitwarden.autofill.match.Credential
import com.framepersecond.m3bitwarden.autofill.match.CredentialKind
import com.framepersecond.m3bitwarden.autofill.match.FillTarget
import com.framepersecond.m3bitwarden.autofill.match.LearnedLink
import com.framepersecond.m3bitwarden.autofill.match.MatchEngine
import com.framepersecond.m3bitwarden.autofill.suggest.Suggester
import com.framepersecond.m3bitwarden.autofill.url.UrlInfo
import com.framepersecond.m3bitwarden.data.VaultSession

/** One row in the iOS credential picker. */
data class ProviderRow(
    val id: String,
    val name: String,
    val username: String,
    /** Website the login belongs to, with subdomain (e.g. "nas.example.com"), so look-alike entries can be told apart. */
    val host: String,
    val hasTotp: Boolean,
    val matched: Boolean,
    val needsConfirmation: Boolean,
)

/**
 * One entry for `ASCredentialIdentityStore`, so iOS can show logins above the keyboard.
 * [rank]: higher is suggested first; the login picked most recently for a site wins.
 */
data class ProviderIdentity(val domain: String, val user: String, val recordId: String, val rank: Int = 0, val hasTotp: Boolean = false)

/** Offer shown after a manual pick: "use this login for <domain> from now on?". */
data class ProviderLinkOffer(val itemId: String, val domain: String, val uri: String)

/**
 * Entry point for the iOS credential provider extension (Swift calls it as
 * `CredentialProviderBridge.shared`). iOS already detects the fields; we rank logins for the
 * service identifiers it hands us and fill the identity store generously, so the QuickType
 * bar has a suggestion on as many sites as possible.
 */
object CredentialProviderBridge {

    // Persistent (app group): picks in the extension teach the next suggestion order.
    private val vault = AutofillVault(SharedPrefsAutofillStore())
    var equivalentDomains: List<Set<String>> = emptyList()

    val isUnlocked: Boolean get() = VaultSession.isUnlocked

    fun markUnlocked() = VaultSession.markUnlocked()

    /**
     * @param identifiers the `ASCredentialServiceIdentifier.identifier` values (domains or URLs).
     * @param nowEpochMs current time, for "recently used first".
     */
    fun rows(identifiers: List<String>, nowEpochMs: Long): List<ProviderRow> {
        val creds = vault.credentials().filter { it.kind == CredentialKind.Login }
        val matcher = MatchEngine(equivalentDomains, learnedLinks = vault.learnedLinks(), nowEpochMs = { nowEpochMs })
        val seen = LinkedHashMap<String, ProviderRow>()
        for (target in identifiers.mapNotNull(::targetFor)) {
            val result = matcher.match(target, creds)
            result.primary.forEach { m -> seen.getOrPut(m.credential.id) { m.credential.row(matched = true, confirm = false) } }
            result.possible.forEach { m -> seen.getOrPut(m.credential.id) { m.credential.row(matched = true, confirm = true) } }
        }
        val rest = creds.filter { it.id !in seen }.sortedBy { it.name.lowercase() }.map { it.row(matched = false, confirm = false) }
        return seen.values.toList() + rest
    }

    fun username(id: String): String? = vault.item(id)?.login?.username
    fun password(id: String): String? = vault.item(id)?.login?.password
    fun totp(id: String, epochSeconds: Long): String? = vault.value(id, ValueKind.Totp, epochSeconds)

    /** Text for iOS 18 "AutoFill → Passwords/Text" in arbitrary fields. */
    fun textFor(id: String, kind: ValueKind, epochSeconds: Long): String? = vault.value(id, kind, epochSeconds)

    fun recordUse(id: String, nowEpochMs: Long) = vault.recordUse(id, nowEpochMs)

    /** Suggestion rank for item [id]: minutes since 1970 of its last use, 0 if never used here. */
    fun rank(id: String): Int = vault.credentials().firstOrNull { it.id == id }?.lastUsedEpochMs?.let { (it / 60_000).toInt() } ?: 0

    /** After a manual pick: a link offer if the item did not already match [identifier]. */
    fun linkOffer(id: String, identifier: String): ProviderLinkOffer? {
        val target = targetFor(identifier) ?: return null
        val cred = vault.credentials().firstOrNull { it.id == id } ?: return null
        val result = MatchEngine(equivalentDomains, learnedLinks = vault.learnedLinks()).match(target, listOf(cred))
        val s = Suggester(vault.dismissed()).afterFill(cred, target, result) ?: return null
        return ProviderLinkOffer(id, s.domain ?: return null, s.uriToAdd)
    }

    fun acceptLink(offer: ProviderLinkOffer) {
        vault.addUri(offer.itemId, offer.uri)
        vault.addLearnedLink(LearnedLink(offer.itemId, domain = offer.domain))
    }

    /**
     * Identities for every web URI of every login, plus the registrable domain and every
     * equivalent domain. Apple matches on the domain, so this widens coverage without
     * revealing anything secret (identities hold no passwords).
     */
    fun identities(): List<ProviderIdentity> {
        val out = LinkedHashSet<ProviderIdentity>()
        for (c in vault.credentials().filter { it.kind == CredentialKind.Login }) {
            val rank = c.lastUsedEpochMs?.let { (it / 60_000).toInt() } ?: 0
            fun identity(domain: String) = ProviderIdentity(domain, c.username, c.id, rank, c.hasTotp)
            for (u in c.uris) {
                val info = UrlInfo.parse(u.uri) ?: continue
                val host = info.host ?: continue
                out += identity(host)
                // Only logins saved for the bare domain (or www.) cover the whole domain. A login for
                // nas.example.com must not be suggested on mail.example.com: iOS matches identities by
                // domain suffix, so announcing the registrable domain would mix all subdomains.
                val reg = info.registrableDomain ?: continue
                if (host != reg && host != "www.$reg") continue
                out += identity(reg)
                equivalentDomains.firstOrNull { reg in it }?.forEach { eq -> out += identity(eq) }
            }
            vault.learnedLinks().filter { it.credentialId == c.id && it.domain != null }.forEach { out += identity(it.domain!!) }
        }
        return out.toList()
    }

    private fun targetFor(identifier: String): FillTarget? {
        val info = UrlInfo.parse(identifier) ?: return null
        val host = info.host ?: return null
        // iOS only passes domains it verified (Safari, or the app's associated domains).
        return FillTarget(
            platform = AppPlatform.Ios,
            isTrustedBrowser = true,
            webDomain = host,
            webScheme = info.scheme ?: "https",
            url = identifier.takeIf { it.contains("://") },
        )
    }

    private fun Credential.row(matched: Boolean, confirm: Boolean) =
        ProviderRow(id, name, username, uris.firstNotNullOfOrNull { UrlInfo.parse(it.uri)?.host?.removePrefix("www.") }.orEmpty(), hasTotp, matched, confirm)
}
