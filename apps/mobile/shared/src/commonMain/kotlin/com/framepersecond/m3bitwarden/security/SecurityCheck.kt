package com.framepersecond.m3bitwarden.security

import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.generator.PasswordGenerator
import com.framepersecond.m3bitwarden.ui.brands.Brands

enum class IssueKind(val short: String) { Reused("Doppelt"), Weak("Schwach"), TwoFactor("2FA möglich") }

data class Issue(val kind: IssueKind, val text: String, val serious: Boolean)

data class Finding(val item: VaultItem, val issues: List<Issue>)

data class SecurityReport(val findings: List<Finding>, val score: Int) {
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
        val findings = logins.mapNotNull { item ->
            val password = item.login!!.password
            val issues = buildList {
                val others = byPassword[password].orEmpty().filter { it.id != item.id }
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
        return SecurityReport(findings, score)
    }
}
