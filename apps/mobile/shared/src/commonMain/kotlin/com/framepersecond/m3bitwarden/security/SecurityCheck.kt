package com.framepersecond.m3bitwarden.security

import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.generator.PasswordGenerator
import com.framepersecond.m3bitwarden.ui.brands.Brands

enum class IssueKind(val short: String) { Reused("Mehrfach"), Weak("Schwach"), TwoFactor("2FA möglich"), Duplicate("Doppelt") }

data class Issue(val kind: IssueKind, val text: String, val serious: Boolean)

data class Finding(val item: VaultItem, val issues: List<Issue>)

/**
 * Logins that are exactly the same: same username, same password, same websites/apps. [keeper]
 * is the one merging keeps (the one with a passkey, else the fullest, else the newest).
 * [mergeable]: all are personal and editable, and at most one has a passkey (a login holds one).
 */
data class DuplicateGroup(val keeper: VaultItem, val others: List<VaultItem>, val mergeable: Boolean) {
    val all: List<VaultItem> get() = listOf(keeper) + others

    /** The keeper with what only the others have: websites, TOTP, notes, custom fields, favorite. */
    fun merged(): VaultItem {
        val login = keeper.login ?: return keeper
        val uris = all.flatMap { it.login?.allUris.orEmpty() }.distinctBy { it.trim().lowercase() }
        val totp = login.totpSecret?.takeIf { it.isNotBlank() } ?: others.firstNotNullOfOrNull { it.login?.totpSecret?.takeIf(String::isNotBlank) }
        val notes = all.map { it.notes.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n\n")
        val fields = all.flatMap { it.fields }.distinctBy { it.name to it.value }
        return keeper.copy(
            login = login.copy(totpSecret = totp).withUris(uris),
            notes = notes,
            fields = fields,
            favorite = all.any { it.favorite },
        )
    }
}

data class SecurityReport(val findings: List<Finding>, val score: Int, val duplicates: List<DuplicateGroup> = emptyList()) {
    val count: Int get() = findings.sumOf { it.issues.size }
    fun forItem(id: String): Finding? = findings.firstOrNull { it.item.id == id }
}

/**
 * Local security check on the decrypted vault in memory. Nothing leaves the device and only
 * flags come out of here, never secrets.
 */
object SecurityCheck {
    /** Sites known to offer two-factor login (subset of 2fa.directory). */
    private val twoFactorSites = setOf(
        "github", "google", "paypal", "discord", "steam", "protonmail", "apple", "dropbox", "reddit", "twitch", "ebay",
        "instagram", "n26", "homeassistant", "gitlab", "cloudflare", "bitwarden", "nextcloud", "proxmox", "docker",
        "figma", "notion", "revolut", "strava", "synology", "gmail", "youtube", "zalando", "duolingo",
    )

    fun run(items: List<VaultItem>): SecurityReport {
        val logins = items.filter { it.type == ItemType.Login && !it.login?.password.isNullOrEmpty() }
        val byPassword = logins.groupBy { it.login!!.password }
        val duplicates = duplicates(logins)
        val sameAs: Map<String, DuplicateGroup> = duplicates.flatMap { g -> g.all.map { it.id to g } }.toMap()
        val findings = logins.mapNotNull { item ->
            val password = item.login!!.password
            val issues = buildList {
                val group = sameAs[item.id]
                if (group != null) {
                    val twins = group.all.filter { it.id != item.id }
                    add(Issue(IssueKind.Duplicate, "Gleicher Eintrag wie ${twins.joinToString(", ") { it.name }}", false))
                }
                // The same account saved twice is not password reuse; only other accounts count.
                val others = byPassword[password].orEmpty().filter { it.id != item.id && (group == null || it.id !in group.all.map(VaultItem::id)) }
                if (others.isNotEmpty()) add(Issue(IssueKind.Reused, "Auch bei ${others.joinToString(", ") { it.name }} verwendet", true))
                if (PasswordGenerator.estimateBits(password) < 50) add(Issue(IssueKind.Weak, "Schwaches Passwort", true))
                val brand = Brands.forItem(item)
                if (item.login.totpSecret.isNullOrBlank() && brand != null && brand.slug in twoFactorSites) {
                    add(Issue(IssueKind.TwoFactor, "Zwei-Faktor-Anmeldung möglich, aber nicht eingerichtet", false))
                }
            }
            if (issues.isEmpty()) null else Finding(item, issues)
        }
        val total = items.count { it.type == ItemType.Login }.coerceAtLeast(1)
        val penalty = findings.sumOf { f -> f.issues.sumOf { if (it.serious) 3 else 1 } }
        val score = (100 - penalty * 100.0 / (total * 4)).toInt().coerceIn(0, 100)
        return SecurityReport(findings, score, duplicates)
    }

    /** Groups of logins with the same username, password and websites/apps (normalized). */
    fun duplicates(logins: List<VaultItem>): List<DuplicateGroup> =
        logins.filter { it.type == ItemType.Login && it.login != null }
            .groupBy { item ->
                val l = item.login!!
                val places = l.allUris.map(::place).filter { it.isNotEmpty() }.distinct().sorted()
                if (places.isEmpty() || l.password.isEmpty()) null else Triple(l.username.trim().lowercase(), l.password, places)
            }
            .filter { (key, group) -> key != null && group.size > 1 }
            .values
            .map { group ->
                val keeper = group.sortedWith(
                    compareByDescending<VaultItem> { (it.login?.passkeys ?: 0) > 0 }
                        .thenByDescending { richness(it) }
                        .thenByDescending { it.revisionEpoch },
                ).first()
                val mergeable = group.all { it.editable && !it.shared } && group.count { (it.login?.passkeys ?: 0) > 0 } <= 1
                DuplicateGroup(keeper, group.filter { it.id != keeper.id }, mergeable)
            }
            .sortedBy { it.keeper.name.lowercase() }

    private fun richness(item: VaultItem): Int =
        (if (item.notes.isNotBlank()) 1 else 0) + (if (!item.login?.totpSecret.isNullOrBlank()) 2 else 0) + item.fields.size + (item.login?.allUris?.size ?: 0)

    /** "example.com" for any web address of that host (www., scheme and path ignored), app ids as they are. */
    private fun place(uri: String): String {
        val u = uri.trim().lowercase()
        if (u.startsWith("androidapp://") || u.startsWith("iosapp://")) return u
        return VaultItem.hostOf(u)
    }
}
