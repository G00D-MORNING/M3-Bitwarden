package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.platform.PlatformPrefs

import com.framepersecond.m3bitwarden.autofill.match.Credential
import com.framepersecond.m3bitwarden.autofill.match.CredentialKind
import com.framepersecond.m3bitwarden.autofill.match.LearnedLink
import com.framepersecond.m3bitwarden.autofill.match.LoginUri
import com.framepersecond.m3bitwarden.data.DemoTotp
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.data.VaultSession

/** Device-local autofill memory: confirmed links and suggestions the user dismissed. */
interface AutofillStore {
    fun learnedLinks(): List<LearnedLink>
    fun addLearnedLink(link: LearnedLink)
    fun dismissed(): Set<String>
    fun dismiss(key: String)
    fun usage(): Map<String, Pair<Long, Int>>
    fun recordUse(itemId: String, epochMs: Long)
}

/**
 * [AutofillStore] in the platform preferences (on iOS the app group, shared by the app and the
 * AutoFill extension), so which login was picked where survives the extension process. Holds only
 * item ids, domains, app ids and use times, never a password or any other vault content.
 */
class SharedPrefsAutofillStore : AutofillStore {
    override fun learnedLinks(): List<LearnedLink> = lines(KEY_LINKS).mapNotNull { line ->
        val p = line.split(SEP)
        if (p.size < 4 || p[0].isEmpty()) null
        else LearnedLink(p[0], p[1].ifEmpty { null }, p[2].ifEmpty { null }, p[3].ifEmpty { null })
    }

    override fun addLearnedLink(link: LearnedLink) {
        val all = (learnedLinks().filterNot { it == link } + link).takeLast(MAX)
        write(KEY_LINKS, all.map { listOf(it.credentialId, it.appId.orEmpty(), it.appCertSha256.orEmpty(), it.domain.orEmpty()).joinToString(SEP) })
    }

    override fun dismissed(): Set<String> = lines(KEY_DISMISSED).toSet()

    override fun dismiss(key: String) = write(KEY_DISMISSED, (lines(KEY_DISMISSED) + key).distinct().takeLast(MAX))

    override fun usage(): Map<String, Pair<Long, Int>> = lines(KEY_USAGE).mapNotNull { line ->
        val p = line.split(SEP)
        val at = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        p[0] to (at to (p.getOrNull(2)?.toIntOrNull() ?: 1))
    }.toMap()

    override fun recordUse(itemId: String, epochMs: Long) {
        val all = usage().toMutableMap()
        all[itemId] = epochMs to ((all[itemId]?.second ?: 0) + 1)
        write(KEY_USAGE, all.entries.sortedBy { it.value.first }.takeLast(MAX).map { "${it.key}$SEP${it.value.first}$SEP${it.value.second}" })
    }

    private fun lines(key: String): List<String> =
        PlatformPrefs.getString(key)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun write(key: String, lines: List<String>) = PlatformPrefs.putString(key, lines.joinToString("\n"))

    private companion object {
        const val KEY_LINKS = "autofill.learnedLinks"
        const val KEY_DISMISSED = "autofill.dismissed"
        const val KEY_USAGE = "autofill.usage"
        const val SEP = "\t"
        const val MAX = 2_000
    }
}

class InMemoryAutofillStore : AutofillStore {
    private val links = mutableListOf<LearnedLink>()
    private val dismissedKeys = mutableSetOf<String>()
    private val uses = mutableMapOf<String, Pair<Long, Int>>()
    override fun learnedLinks() = links.toList()
    override fun addLearnedLink(link: LearnedLink) { links += link }
    override fun dismissed() = dismissedKeys.toSet()
    override fun dismiss(key: String) { dismissedKeys += key }
    override fun usage() = uses.toMap()
    override fun recordUse(itemId: String, epochMs: Long) {
        uses[itemId] = epochMs to ((uses[itemId]?.second ?: 0) + 1)
    }
}

/**
 * Connects the vault to the autofill engine: hands out metadata for matching and resolves
 * secrets only for the one item the user picked. Secrets are never cached here.
 */
class AutofillVault(private val store: AutofillStore) {

    val isUnlocked: Boolean get() = VaultSession.isUnlocked

    fun credentials(): List<Credential> {
        val usage = store.usage()
        return VaultSession.repository.items.mapNotNull { item ->
            val kind = when (item.type) {
                ItemType.Login -> CredentialKind.Login
                ItemType.Card -> CredentialKind.Card
                ItemType.Identity -> CredentialKind.Identity
                else -> return@mapNotNull null
            }
            val use = usage[item.id]
            Credential(
                id = item.id,
                name = item.name,
                kind = kind,
                username = item.login?.username.orEmpty(),
                uris = item.login?.allUris.orEmpty().map { LoginUri(it) },
                favorite = item.favorite,
                hasTotp = item.login?.totpSecret != null,
                lastUsedEpochMs = use?.first,
                useCount = use?.second ?: 0,
            )
        }
    }

    fun item(id: String): VaultItem? = VaultSession.repository.get(id)

    /** The value for one field of the picked item. [otpDigitIndex] selects one digit of a split code. */
    fun value(id: String, kind: ValueKind, epochSeconds: Long, otpDigitIndex: Int = -1): String? {
        val item = item(id) ?: return null
        val login = item.login
        val card = item.card
        val identity = item.identity
        val v = when (kind) {
            ValueKind.Username -> login?.username ?: identity?.username?.ifEmpty { identity.email }
            ValueKind.Password -> login?.password
            ValueKind.Totp -> login?.totpSecret?.let { totp(it, epochSeconds) }
            ValueKind.CardNumber -> card?.number
            ValueKind.CardHolder -> card?.cardholder
            // Canonical forms; ValueFormatter adapts them to each field ("08/29", "0829", option index).
            ValueKind.CardExpiry -> card?.let { c -> c.expMonth.takeIf { it.isNotEmpty() }?.let { m -> "${m.padStart(2, '0')}/${fullYear(c.expYear)}" } }
            ValueKind.CardExpiryMonth -> card?.expMonth
            ValueKind.CardExpiryYear -> card?.expYear?.let(::fullYear)
            ValueKind.CardCvv -> card?.code
            ValueKind.CardBrand -> card?.brand
            ValueKind.FullName -> identity?.let { listOf(it.firstName, it.middleName, it.lastName).filter(String::isNotBlank).joinToString(" ") } ?: card?.cardholder
            ValueKind.FirstName -> identity?.firstName
            ValueKind.LastName -> identity?.lastName
            ValueKind.Email -> identity?.email ?: login?.username?.takeIf { '@' in it }
            ValueKind.Phone, ValueKind.PhoneCountryCode, ValueKind.PhoneAreaCode, ValueKind.PhoneLocal -> identity?.phone
            // The house number may be kept on its own line; joined here, split again per field.
            ValueKind.Address, ValueKind.Street, ValueKind.HouseNumber -> identity?.let { com.framepersecond.m3bitwarden.autofill.format.HouseNumberJoiner.join(it.address1, it.address2, it.address3).line }
            ValueKind.AddressLine2 -> identity?.let { com.framepersecond.m3bitwarden.autofill.format.HouseNumberJoiner.join(it.address1, it.address2, it.address3).rest.joinToString(", ") }
            // Imports often keep the postal code inside the city or the address line.
            ValueKind.PostalCode -> identity?.let { com.framepersecond.m3bitwarden.autofill.format.PostalSplitter.resolve(it.postalCode, it.city, listOf(it.address1, it.address2, it.address3)).postalCode }
            ValueKind.City -> identity?.let { com.framepersecond.m3bitwarden.autofill.format.PostalSplitter.resolve(it.postalCode, it.city, listOf(it.address1, it.address2, it.address3)).city }
            ValueKind.State -> identity?.state
            ValueKind.Country -> identity?.country
            ValueKind.Organization -> identity?.company
            ValueKind.Title -> identity?.title
        }?.takeIf { it.isNotEmpty() } ?: return null
        return if (otpDigitIndex >= 0) v.getOrNull(otpDigitIndex)?.toString() else v
    }

    private fun fullYear(y: String): String = y.trim().let { if (it.length == 2 && it.all(Char::isDigit)) "20$it" else it }

    /**
     * The value for [assignment], shaped for its field (format, length, list option).
     * Returns null when the item has nothing that fits.
     */
    fun fill(id: String, assignment: com.framepersecond.m3bitwarden.autofill.FieldAssignment, epochSeconds: Long): com.framepersecond.m3bitwarden.autofill.format.FillValue? {
        val raw = value(id, assignment.value, epochSeconds, assignment.otpDigitIndex)
        return com.framepersecond.m3bitwarden.autofill.format.ValueFormatter.format(assignment.value, raw, assignment.format)
    }

    /** Real codes come from the backend (Bitwarden SDK); the demo generator is only for sample data. */
    private fun totp(secret: String, epochSeconds: Long): String? {
        val backend = VaultSession.backend ?: return DemoTotp.code(secret, epochSeconds)
        return backend.totpCode(secret, epochSeconds)
    }

    /** Constant-time comparison so timing does not reveal how much of a password matched. */
    fun passwordEquals(id: String, candidate: String): Boolean {
        val stored = item(id)?.login?.password ?: return false
        if (stored.length != candidate.length) return false
        var diff = 0
        for (i in stored.indices) diff = diff or (stored[i].code xor candidate[i].code)
        return diff == 0
    }

    fun recordUse(id: String, epochMs: Long) = store.recordUse(id, epochMs)

    /** Adds a website or app URI to the item; with a backend it is encrypted and uploaded. */
    fun addUri(id: String, uri: String) {
        val item = item(id) ?: return
        val login = item.login ?: return
        if (login.allUris.any { it.equals(uri, ignoreCase = true) }) return
        VaultSession.repository.save(item.copy(login = login.copy(additionalUris = login.additionalUris + uri)))
    }

    fun updatePassword(id: String, password: String) {
        val item = item(id) ?: return
        val login = item.login ?: return
        VaultSession.repository.save(item.copy(login = login.copy(password = password)))
    }

    fun saveNew(name: String, username: String, password: String, uri: String?): String =
        VaultSession.repository.save(
            VaultItem(
                id = "",
                name = name,
                type = ItemType.Login,
                login = com.framepersecond.m3bitwarden.data.LoginData(username = username, password = password, uri = uri.orEmpty()),
                updatedLabel = "gerade eben",
            ),
        ).id

    fun learnedLinks() = store.learnedLinks()
    fun addLearnedLink(link: LearnedLink) = store.addLearnedLink(link)
    fun dismissed() = store.dismissed()
    fun dismiss(key: String) = store.dismiss(key)
}
