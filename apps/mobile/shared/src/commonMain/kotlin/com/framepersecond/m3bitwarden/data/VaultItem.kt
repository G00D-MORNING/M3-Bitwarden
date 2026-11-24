package com.framepersecond.m3bitwarden.data

import androidx.compose.runtime.Immutable

enum class ItemType {
    Login,
    Card,
    Identity,
    Note,
    SshKey,
}

@Immutable
data class Folder(val id: String, val name: String)

@Immutable
data class VaultItem(
    val id: String,
    val name: String,
    val type: ItemType,
    /** Display name of the folder (decrypted), null when the item has none. */
    val folder: String? = null,
    val folderId: String? = null,
    val favorite: Boolean = false,
    val notes: String = "",
    val login: LoginData? = null,
    val card: CardData? = null,
    val identity: IdentityData? = null,
    val sshKey: SshKeyData? = null,
    val fields: List<CustomField> = emptyList(),
    val passwordHistory: List<PasswordHistoryEntry> = emptyList(),
    val updatedLabel: String = "",
    /** Last change on the server (epoch seconds), for sorting. */
    val revisionEpoch: Long = 0,
    /** False for organization items the account may only read. */
    val editable: Boolean = true,
    /** Belongs to an organization (shared), shown as a hint. */
    val shared: Boolean = false,
) {
    /** One-line hint shown under the name in lists. Never contains a secret. */
    val subtitle: String
        get() = when (type) {
            ItemType.Login -> login?.username?.ifEmpty { login.allUris.firstOrNull()?.let(::hostOf) }.orEmpty()
            ItemType.Card -> card?.let { c ->
                listOf(c.brand, c.number.takeLast(4).takeIf { it.isNotEmpty() }?.let { "*$it" })
                    .filterNot { it.isNullOrEmpty() }.joinToString(", ")
            }.orEmpty()
            ItemType.Identity -> identity?.fullName?.ifEmpty { identity.email }.orEmpty()
            ItemType.Note -> notes.lineSequence().firstOrNull().orEmpty()
            ItemType.SshKey -> sshKey?.fingerprint?.take(24)?.plus("…").orEmpty()
        }

    /** The value a user copies most often from this item, used by the quick-copy button. */
    val primarySecret: String?
        get() = when (type) {
            ItemType.Login -> login?.password
            ItemType.Card -> card?.number
            ItemType.SshKey -> sshKey?.publicKey
            else -> null
        }?.takeIf { it.isNotEmpty() }

    companion object {
        fun hostOf(uri: String): String {
            val noScheme = uri.substringAfter("://", uri)
            return noScheme.substringBefore('/').substringBefore('?').substringAfter('@').removePrefix("www.")
        }

        /** Empty item of [type], used by the editor for new entries. */
        fun blank(type: ItemType, folderId: String? = null): VaultItem = VaultItem(
            id = "",
            name = "",
            type = type,
            folderId = folderId,
            login = if (type == ItemType.Login) LoginData() else null,
            card = if (type == ItemType.Card) CardData() else null,
            identity = if (type == ItemType.Identity) IdentityData() else null,
            sshKey = if (type == ItemType.SshKey) SshKeyData() else null,
        )
    }
}

@Immutable
data class LoginData(
    val username: String = "",
    val password: String = "",
    val uri: String = "",
    val totpSecret: String? = null,
    /** Further websites and apps (`androidapp://…`, `iosapp://…`) this login belongs to. */
    val additionalUris: List<String> = emptyList(),
    /** Number of passkeys stored with this login (read-only in this app). */
    val passkeys: Int = 0,
    /** Site and account of each passkey, when the platform could read them. */
    val passkeyDetails: List<PasskeyInfo> = emptyList(),
) {
    val allUris: List<String> get() = (listOf(uri) + additionalUris).filter { it.isNotBlank() }.distinct()

    fun withUris(uris: List<String>): LoginData {
        val clean = uris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return copy(uri = clean.firstOrNull().orEmpty(), additionalUris = clean.drop(1))
    }
}

/** A passkey as the item page shows it; the key itself never leaves the Bitwarden SDK. */
@Immutable
data class PasskeyInfo(val site: String, val userName: String, val createdLabel: String)

@Immutable
data class CardData(
    val cardholder: String = "",
    val brand: String = "",
    val number: String = "",
    val expMonth: String = "",
    val expYear: String = "",
    val code: String = "",
)

@Immutable
data class IdentityData(
    val title: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val username: String = "",
    val company: String = "",
    val email: String = "",
    val phone: String = "",
    val address1: String = "",
    val address2: String = "",
    val address3: String = "",
    val postalCode: String = "",
    val city: String = "",
    val state: String = "",
    val country: String = "",
    val ssn: String = "",
    val passportNumber: String = "",
    val licenseNumber: String = "",
) {
    val fullName: String
        get() = listOf(title, firstName, middleName, lastName).filter { it.isNotBlank() }.joinToString(" ")

    /** Multi-line postal address for display. */
    val address: String
        get() = listOf(
            address1, address2, address3,
            listOf(postalCode, city).filter { it.isNotBlank() }.joinToString(" "),
            state, country,
        ).filter { it.isNotBlank() }.joinToString("\n")
}

@Immutable
data class SshKeyData(
    val publicKey: String = "",
    val fingerprint: String = "",
    val privateKey: String = "",
)

enum class CustomFieldType { Text, Hidden, Boolean, Linked }

@Immutable
data class CustomField(
    val name: String = "",
    val value: String = "",
    val type: CustomFieldType = CustomFieldType.Text,
)

@Immutable
data class PasswordHistoryEntry(val password: String, val dateLabel: String)
