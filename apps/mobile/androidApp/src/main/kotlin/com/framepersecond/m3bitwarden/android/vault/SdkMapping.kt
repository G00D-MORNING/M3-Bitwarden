package com.framepersecond.m3bitwarden.android.vault

import android.text.format.DateUtils
import com.bitwarden.vault.Attachment
import com.bitwarden.vault.Card
import com.bitwarden.vault.CardView
import com.bitwarden.vault.Cipher
import com.bitwarden.vault.CipherPermissions
import com.bitwarden.vault.CipherRepromptType
import com.bitwarden.vault.CipherType
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.Fido2Credential
import com.bitwarden.vault.Field
import com.bitwarden.vault.FieldType
import com.bitwarden.vault.FieldView
import com.bitwarden.vault.Identity
import com.bitwarden.vault.IdentityView
import com.bitwarden.vault.Login
import com.bitwarden.vault.LoginUri
import com.bitwarden.vault.LoginUriView
import com.bitwarden.vault.LoginView
import com.bitwarden.vault.PasswordHistory
import com.bitwarden.vault.PasswordHistoryView
import com.bitwarden.vault.SecureNote
import com.bitwarden.vault.SecureNoteType
import com.bitwarden.vault.SecureNoteView
import com.bitwarden.vault.SshKey
import com.bitwarden.vault.SshKeyView
import com.bitwarden.vault.UriMatchType
import com.framepersecond.m3bitwarden.data.CardData
import com.framepersecond.m3bitwarden.data.CustomField
import com.framepersecond.m3bitwarden.data.CustomFieldType
import com.framepersecond.m3bitwarden.data.IdentityData
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.LoginData
import com.framepersecond.m3bitwarden.data.PasskeyInfo
import com.framepersecond.m3bitwarden.data.PasswordHistoryEntry
import com.framepersecond.m3bitwarden.data.SshKeyData
import com.framepersecond.m3bitwarden.data.VaultItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// Conversions between the server JSON, the Bitwarden SDK types and the Kotlin UI model.
// Field-for-field ports of the official Bitwarden mappers (GPL-3.0), same as iosApp/Common/SdkMapping.swift.

// MARK: Server JSON → SDK (encrypted)

fun cipherFromJson(m: JSONObject): Cipher {
    val revision = m.date("revisionDate") ?: Instant.now()
    return Cipher(
        id = m.str("id"),
        organizationId = m.str("organizationId"),
        folderId = m.str("folderId"),
        collectionIds = m.arr("collectionIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        key = m.str("key"),
        name = m.str("name"),
        notes = m.str("notes"),
        type = cipherType(m.int("type") ?: 1),
        login = m.obj("login")?.let(::loginFromJson),
        identity = m.obj("identity")?.let(::identityFromJson),
        card = m.obj("card")?.let { c ->
            Card(c.str("cardholderName"), c.str("expMonth"), c.str("expYear"), c.str("code"), c.str("brand"), c.str("number"))
        },
        secureNote = m.obj("secureNote")?.let { SecureNote(SecureNoteType.GENERIC) },
        sshKey = m.obj("sshKey")?.let { k -> k.str("privateKey")?.let { SshKey(it, k.str("publicKey"), k.str("keyFingerprint")) } },
        bankAccount = null,
        driversLicense = null,
        passport = null,
        favorite = m.bool("favorite") ?: false,
        reprompt = if (m.int("reprompt") == 1) CipherRepromptType.PASSWORD else CipherRepromptType.NONE,
        organizationUseTotp = m.bool("organizationUseTotp") ?: false,
        edit = m.bool("edit") ?: true,
        permissions = m.obj("permissions")?.let { CipherPermissions(it.bool("delete") ?: true, it.bool("restore") ?: true) },
        viewPassword = m.bool("viewPassword") ?: true,
        localData = null,
        attachments = m.arr("attachments")?.objects()?.map {
            Attachment(it.str("id"), it.str("url"), it.str("size"), it.str("sizeName"), it.str("fileName"), it.str("key"))
        },
        fields = m.arr("fields")?.objects()?.map {
            Field(it.str("name"), it.str("value"), fieldType(it.int("type") ?: 0), it.int("linkedId")?.toUInt())
        },
        passwordHistory = m.arr("passwordHistory")?.objects()?.mapNotNull { h ->
            val pw = h.str("password") ?: return@mapNotNull null
            PasswordHistory(pw, h.date("lastUsedDate") ?: revision)
        },
        creationDate = m.date("creationDate") ?: revision,
        deletedDate = m.date("deletedDate"),
        revisionDate = revision,
        archivedDate = m.date("archivedDate"),
        data = null,
        partialData = null,
    )
}

private fun loginFromJson(l: JSONObject) = Login(
    username = l.str("username"),
    password = l.str("password"),
    passwordRevisionDate = l.date("passwordRevisionDate"),
    uris = l.arr("uris")?.objects()?.map { LoginUri(it.str("uri"), it.int("match")?.let(::uriMatch), it.str("uriChecksum")) },
    totp = l.str("totp"),
    autofillOnPageLoad = l.bool("autofillOnPageLoad"),
    fido2Credentials = l.arr("fido2Credentials")?.objects()?.mapNotNull { f ->
        Fido2Credential(
            credentialId = f.str("credentialId") ?: return@mapNotNull null,
            keyType = f.str("keyType") ?: return@mapNotNull null,
            keyAlgorithm = f.str("keyAlgorithm") ?: return@mapNotNull null,
            keyCurve = f.str("keyCurve") ?: return@mapNotNull null,
            keyValue = f.str("keyValue") ?: return@mapNotNull null,
            rpId = f.str("rpId") ?: return@mapNotNull null,
            userHandle = f.str("userHandle"),
            userName = f.str("userName"),
            counter = f.str("counter") ?: return@mapNotNull null,
            rpName = f.str("rpName"),
            userDisplayName = f.str("userDisplayName"),
            discoverable = f.str("discoverable") ?: return@mapNotNull null,
            creationDate = f.date("creationDate") ?: Instant.now(),
        )
    },
)

private fun identityFromJson(i: JSONObject) = Identity(
    i.str("title"), i.str("firstName"), i.str("middleName"), i.str("lastName"),
    i.str("address1"), i.str("address2"), i.str("address3"), i.str("city"), i.str("state"),
    i.str("postalCode"), i.str("country"), i.str("company"), i.str("email"), i.str("phone"),
    i.str("ssn"), i.str("username"), i.str("passportNumber"), i.str("licenseNumber"),
)

private fun cipherType(server: Int) = when (server) {
    2 -> CipherType.SECURE_NOTE
    3 -> CipherType.CARD
    4 -> CipherType.IDENTITY
    5 -> CipherType.SSH_KEY
    else -> CipherType.LOGIN
}

private fun CipherType.serverValue() = when (this) {
    CipherType.LOGIN -> 1
    CipherType.SECURE_NOTE -> 2
    CipherType.CARD -> 3
    CipherType.IDENTITY -> 4
    CipherType.SSH_KEY -> 5
    else -> 2
}

private fun fieldType(server: Int) = when (server) {
    1 -> FieldType.HIDDEN
    2 -> FieldType.BOOLEAN
    3 -> FieldType.LINKED
    else -> FieldType.TEXT
}

private fun uriMatch(server: Int) = UriMatchType.entries.firstOrNull { it.value.toInt() == server }

// MARK: SDK (encrypted) → request body

fun Cipher.toRequestJson(): JSONObject = JSONObject().apply {
    put("type", type.serverValue())
    putOpt("organizationId", organizationId)
    putOpt("folderId", folderId)
    putOpt("key", key)
    putOpt("name", name)
    putOpt("notes", notes)
    put("favorite", favorite)
    put("reprompt", if (reprompt == CipherRepromptType.PASSWORD) 1 else 0)
    login?.let { l ->
        put("login", JSONObject().apply {
            putOpt("username", l.username)
            putOpt("password", l.password)
            putOpt("passwordRevisionDate", l.passwordRevisionDate?.iso())
            putOpt("uris", l.uris?.let { uris ->
                JSONArray(uris.map { JSONObject().putOpt("uri", it.uri).putOpt("match", it.match?.value?.toInt()).putOpt("uriChecksum", it.uriChecksum) })
            })
            putOpt("totp", l.totp)
            putOpt("autofillOnPageLoad", l.autofillOnPageLoad)
            putOpt("fido2Credentials", l.fido2Credentials?.let { list ->
                JSONArray(list.map { f ->
                    JSONObject().put("credentialId", f.credentialId).put("keyType", f.keyType).put("keyAlgorithm", f.keyAlgorithm)
                        .put("keyCurve", f.keyCurve).put("keyValue", f.keyValue).put("rpId", f.rpId).putOpt("userHandle", f.userHandle)
                        .putOpt("userName", f.userName).put("counter", f.counter).putOpt("rpName", f.rpName)
                        .putOpt("userDisplayName", f.userDisplayName).put("discoverable", f.discoverable).put("creationDate", f.creationDate.iso())
                })
            })
        })
    }
    card?.let { c ->
        put("card", JSONObject().putOpt("cardholderName", c.cardholderName).putOpt("brand", c.brand).putOpt("number", c.number)
            .putOpt("expMonth", c.expMonth).putOpt("expYear", c.expYear).putOpt("code", c.code))
    }
    identity?.let { i ->
        put("identity", JSONObject().putOpt("title", i.title).putOpt("firstName", i.firstName).putOpt("middleName", i.middleName)
            .putOpt("lastName", i.lastName).putOpt("address1", i.address1).putOpt("address2", i.address2).putOpt("address3", i.address3)
            .putOpt("city", i.city).putOpt("state", i.state).putOpt("postalCode", i.postalCode).putOpt("country", i.country)
            .putOpt("company", i.company).putOpt("email", i.email).putOpt("phone", i.phone).putOpt("ssn", i.ssn)
            .putOpt("username", i.username).putOpt("passportNumber", i.passportNumber).putOpt("licenseNumber", i.licenseNumber))
    }
    secureNote?.let { put("secureNote", JSONObject().put("type", 0)) }
    sshKey?.let { k -> put("sshKey", JSONObject().put("privateKey", k.privateKey).putOpt("publicKey", k.publicKey).putOpt("keyFingerprint", k.fingerprint)) }
    fields?.let { list ->
        put("fields", JSONArray(list.map { f ->
            JSONObject().putOpt("name", f.name).putOpt("value", f.value).put("type", f.type.value.toInt()).putOpt("linkedId", f.linkedId?.toInt())
        }))
    }
    passwordHistory?.let { list ->
        put("passwordHistory", JSONArray(list.map { JSONObject().put("password", it.password).put("lastUsedDate", it.lastUsedDate.iso()) }))
    }
    if (id != null) put("lastKnownRevisionDate", revisionDate.iso())
}

// MARK: SDK (decrypted) → Kotlin UI model

private fun relative(instant: Instant): String =
    DateUtils.getRelativeTimeSpanString(instant.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun CipherView.toItem(folders: Map<String, String>, passkeys: List<PasskeyInfo> = emptyList()): VaultItem? {
    val id = id ?: return null
    val uris = login?.uris.orEmpty().mapNotNull { it.uri }.filter { it.isNotEmpty() }
    val kind = when (type) {
        CipherType.LOGIN -> ItemType.Login
        CipherType.CARD -> ItemType.Card
        CipherType.IDENTITY -> ItemType.Identity
        CipherType.SSH_KEY -> ItemType.SshKey
        else -> ItemType.Note
    }
    return VaultItem(
        id = id,
        name = name,
        type = kind,
        folder = folderId?.let { folders[it] },
        folderId = folderId,
        favorite = favorite,
        notes = notes.orEmpty(),
        login = login?.let {
            LoginData(
                username = it.username.orEmpty(),
                password = it.password.orEmpty(),
                uri = uris.firstOrNull().orEmpty(),
                totpSecret = it.totp,
                additionalUris = uris.drop(1),
                passkeys = it.fido2Credentials?.size ?: 0,
                passkeyDetails = passkeys,
            )
        },
        card = card?.let { CardData(it.cardholderName.orEmpty(), it.brand.orEmpty(), it.number.orEmpty(), it.expMonth.orEmpty(), it.expYear.orEmpty(), it.code.orEmpty()) },
        identity = identity?.let { i ->
            IdentityData(
                title = i.title.orEmpty(), firstName = i.firstName.orEmpty(), middleName = i.middleName.orEmpty(), lastName = i.lastName.orEmpty(),
                username = i.username.orEmpty(), company = i.company.orEmpty(), email = i.email.orEmpty(), phone = i.phone.orEmpty(),
                address1 = i.address1.orEmpty(), address2 = i.address2.orEmpty(), address3 = i.address3.orEmpty(),
                postalCode = i.postalCode.orEmpty(), city = i.city.orEmpty(), state = i.state.orEmpty(), country = i.country.orEmpty(),
                ssn = i.ssn.orEmpty(), passportNumber = i.passportNumber.orEmpty(), licenseNumber = i.licenseNumber.orEmpty(),
            )
        },
        sshKey = sshKey?.let { SshKeyData(it.publicKey, it.fingerprint, it.privateKey) },
        fields = fields.orEmpty().map { CustomField(it.name.orEmpty(), it.value.orEmpty(), it.type.toUi()) },
        passwordHistory = passwordHistory.orEmpty().map { PasswordHistoryEntry(it.password, relative(it.lastUsedDate)) },
        updatedLabel = relative(revisionDate),
        revisionEpoch = revisionDate.epochSecond,
        editable = edit,
        shared = organizationId != null,
    )
}

private fun FieldType.toUi() = when (this) {
    FieldType.HIDDEN -> CustomFieldType.Hidden
    FieldType.BOOLEAN -> CustomFieldType.Boolean
    FieldType.LINKED -> CustomFieldType.Linked
    else -> CustomFieldType.Text
}

private fun CustomFieldType.toSdk() = when (this) {
    CustomFieldType.Hidden -> FieldType.HIDDEN
    CustomFieldType.Boolean -> FieldType.BOOLEAN
    CustomFieldType.Linked -> FieldType.LINKED
    CustomFieldType.Text -> FieldType.TEXT
}

private fun String.orNull() = ifEmpty { null }

private fun CardData.toView() = CardView(cardholder.orNull(), expMonth.orNull(), expYear.orNull(), code.orNull(), brand.orNull(), number.orNull())

private fun IdentityData.toView() = IdentityView(
    title.orNull(), firstName.orNull(), middleName.orNull(), lastName.orNull(),
    address1.orNull(), address2.orNull(), address3.orNull(), city.orNull(), state.orNull(),
    postalCode.orNull(), country.orNull(), company.orNull(), email.orNull(), phone.orNull(),
    ssn.orNull(), username.orNull(), passportNumber.orNull(), licenseNumber.orNull(),
)

private fun SshKeyData.toView() = SshKeyView(privateKey, publicKey, fingerprint)

/**
 * Applies what the UI can change to this decrypted cipher, keeping everything else
 * (attachments, passkeys, URI match rules, linked fields) intact.
 */
fun CipherView.applying(item: VaultItem, newFolderId: String?, now: Instant = Instant.now()): CipherView {
    val history = passwordHistory.orEmpty().toMutableList()
    var newLogin = login
    val l = login
    val k = item.login
    if (l != null && k != null) {
        var revision = l.passwordRevisionDate
        val old = l.password
        if (!old.isNullOrEmpty() && old != k.password) {
            history.add(0, PasswordHistoryView(old, now))
            revision = now
        }
        val existing = l.uris.orEmpty().filter { it.uri != null }.associateBy { it.uri!!.lowercase() }
        newLogin = l.copy(
            username = k.username.orNull(),
            password = k.password.orNull(),
            passwordRevisionDate = revision,
            uris = k.allUris.map { existing[it.lowercase()] ?: LoginUriView(it, null, null) },
            totp = k.totpSecret,
        )
    }
    val oldHidden = fields.orEmpty().filter { it.type == FieldType.HIDDEN }.associate { it.name to it.value }
    item.fields.filter { it.type == CustomFieldType.Hidden }.forEach { f ->
        val previous = oldHidden[f.name]
        if (!previous.isNullOrEmpty() && previous != f.value) history.add(0, PasswordHistoryView("${f.name}: $previous", now))
    }
    val linked = fields.orEmpty().filter { it.type == FieldType.LINKED }
    val newFields = item.fields.filter { it.type != CustomFieldType.Linked }.map { FieldView(it.name, it.value, it.type.toSdk(), null) } + linked

    return copy(
        folderId = newFolderId,
        name = item.name,
        notes = item.notes.orNull(),
        favorite = item.favorite,
        login = newLogin,
        card = item.card?.toView() ?: card,
        identity = item.identity?.toView() ?: identity,
        sshKey = item.sshKey?.toView() ?: sshKey,
        fields = newFields.ifEmpty { null },
        passwordHistory = history.take(5).ifEmpty { null },
    )
}

/** A new item created in the app or saved through autofill. */
fun newCipherView(item: VaultItem, folderId: String?, now: Instant = Instant.now()): CipherView {
    val type = when (item.type) {
        ItemType.Card -> CipherType.CARD
        ItemType.Identity -> CipherType.IDENTITY
        ItemType.Note -> CipherType.SECURE_NOTE
        ItemType.SshKey -> CipherType.SSH_KEY
        ItemType.Login -> CipherType.LOGIN
    }
    val k = item.login
    val fields = item.fields.filter { it.type != CustomFieldType.Linked }.map { FieldView(it.name, it.value, it.type.toSdk(), null) }
    return CipherView(
        id = null, organizationId = null, folderId = folderId, collectionIds = emptyList(), key = null,
        name = item.name, notes = item.notes.orNull(), type = type,
        login = if (type != CipherType.LOGIN) null else LoginView(
            username = k?.username?.orNull(),
            password = k?.password?.orNull(),
            passwordRevisionDate = null,
            uris = k?.allUris.orEmpty().map { LoginUriView(it, null, null) },
            totp = k?.totpSecret,
            autofillOnPageLoad = null,
            fido2Credentials = null,
        ),
        identity = if (type == CipherType.IDENTITY) item.identity?.toView() else null,
        card = if (type == CipherType.CARD) item.card?.toView() else null,
        secureNote = if (type == CipherType.SECURE_NOTE) SecureNoteView(SecureNoteType.GENERIC) else null,
        sshKey = if (type == CipherType.SSH_KEY) item.sshKey?.toView() else null,
        bankAccount = null, driversLicense = null, passport = null,
        favorite = item.favorite, reprompt = CipherRepromptType.NONE, organizationUseTotp = false, edit = true, permissions = null,
        viewPassword = true, localData = null, attachments = null, attachmentDecryptionFailures = null,
        fields = fields.ifEmpty { null },
        passwordHistory = null, creationDate = now, deletedDate = null, revisionDate = now, archivedDate = null, partial = false,
    )
}
