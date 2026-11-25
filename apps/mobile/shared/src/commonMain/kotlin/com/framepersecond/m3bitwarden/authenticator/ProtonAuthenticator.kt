package com.framepersecond.m3bitwarden.authenticator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.platform.PlatformPrefs

/** One code from the Proton Authenticator. [uri] holds the TOTP secret: memory only. */
data class AuthEntry(val id: String, val issuer: String, val name: String, val uri: String, val period: Int)

/**
 * What the platform does for the Proton Authenticator (Swift and Android): browser sign-in,
 * sync through the Rust core, codes. The session lives in the platform keychain.
 */
interface ProtonBackend {
    /** Opens Proton's sign-in in the browser and waits for it. */
    fun connect()

    fun cancelConnect()

    /** Loads the entries again (renews the session if needed). */
    fun refresh()

    /** Signs this app out at Proton and forgets the session. */
    fun disconnect()

    fun code(uri: String, epochSeconds: Long): String?
}

/**
 * The Proton Authenticator inside the app: entries in memory while the vault is open, and which
 * login each one belongs to. Linking writes the code into that login in Vaultwarden, so the
 * existing one-time-code autofill on iPhone and Android fills it into 2FA fields.
 */
object ProtonAuthenticator {
    enum class Status { Disconnected, SigningIn, Syncing, Ready, Failed }

    var backend: ProtonBackend? = null
    var status by mutableStateOf(Status.Disconnected)
    var message by mutableStateOf<String?>(null)
    val entries = mutableStateListOf<AuthEntry>()

    val connected: Boolean get() = status == Status.Syncing || status == Status.Ready || (status == Status.Failed && entries.isNotEmpty())

    // Called by the platform.
    fun publish(list: List<AuthEntry>) {
        entries.clear()
        entries.addAll(list.sortedBy { (it.issuer.ifBlank { it.name }).lowercase() })
        status = Status.Ready
        message = null
    }

    fun setStatus(value: Status, text: String? = null) {
        status = value
        message = text
    }

    /** On lock: the codes leave memory with the vault. */
    fun clear() {
        entries.clear()
        if (status != Status.Disconnected) status = Status.Syncing
    }

    // MARK: Links (entry id → login id), kept in the app's preferences (ids only)

    private const val KEY_LINKS = "proton.links"
    private const val KEY_SKIPPED = "proton.skipped"

    private var links by mutableStateOf(readMap(KEY_LINKS))
    private var skipped by mutableStateOf(PlatformPrefs.getString(KEY_SKIPPED)?.split('\n')?.filter(String::isNotBlank)?.toSet().orEmpty())

    fun linkedItemId(entry: AuthEntry): String? = links[entry.id]?.takeIf { VaultSession.repository.get(it) != null }

    /** Entries still waiting for the user to assign (new ones and those of the first setup). */
    val unassigned: List<AuthEntry> get() = entries.filter { linkedItemId(it) == null && it.id !in skipped }

    /** Puts the entry's code into [item] (saved to Vaultwarden) and remembers the link. */
    fun assign(entry: AuthEntry, item: VaultItem) {
        val login = item.login ?: return
        VaultSession.repository.save(item.copy(login = login.copy(totpSecret = entry.uri)))
        links = links + (entry.id to item.id)
        writeMap(KEY_LINKS, links)
    }

    fun skip(entry: AuthEntry) {
        skipped = skipped + entry.id
        PlatformPrefs.putString(KEY_SKIPPED, skipped.joinToString("\n"))
    }

    fun unlink(entry: AuthEntry) {
        links = links - entry.id
        writeMap(KEY_LINKS, links)
    }

    /**
     * Logins that probably belong to [entry], best first, only realistic ones: the issuer names the
     * site (in the login's name or website) and, if the entry names an account, the username fits.
     */
    fun suggestions(entry: AuthEntry, items: List<VaultItem>): List<VaultItem> {
        val issuer = normalize(entry.issuer.ifBlank { entry.name.substringBefore(':') })
        val account = entry.name.substringAfter(':').trim().lowercase()
        return items.filter { it.type == ItemType.Login && it.login != null }
            .mapNotNull { item ->
                val login = item.login!!
                val hosts = login.allUris.map { normalize(VaultItem.hostOf(it)) }
                val name = normalize(item.name)
                var score = 0
                if (issuer.length >= 3) {
                    if (hosts.any { it.contains(issuer) || (issuer.contains(it) && it.length >= 4) }) score += 50
                    if (name.contains(issuer) || (issuer.contains(name) && name.length >= 4)) score += 40
                }
                if (score == 0) return@mapNotNull null
                val user = login.username.trim().lowercase()
                if (account.isNotEmpty() && user.isNotEmpty()) {
                    if (user == account) score += 40 else if (account.contains(user) || user.contains(account)) score += 15 else score -= 20
                }
                if (!login.totpSecret.isNullOrBlank()) score -= 10
                item to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(3)
    }

    private fun normalize(s: String): String =
        s.lowercase().removePrefix("www.").substringBeforeLast('.').filter(Char::isLetterOrDigit)

    private fun readMap(key: String): Map<String, String> =
        PlatformPrefs.getString(key)?.split('\n')?.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[0] to parts[1] else null
        }?.toMap().orEmpty()

    private fun writeMap(key: String, map: Map<String, String>) =
        PlatformPrefs.putString(key, map.entries.joinToString("\n") { "${it.key}\t${it.value}" })
}
