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
data class VaultItem(
    val id: String,
    val name: String,
    val type: ItemType,
    val folder: String? = null,
    val favorite: Boolean = false,
    val notes: String = "",
    val login: LoginData? = null,
    val card: CardData? = null,
    val identity: IdentityData? = null,
    val sshKey: SshKeyData? = null,
    val updatedLabel: String = "",
) {
    /** One-line hint shown under the name in lists. Never contains a secret. */
    val subtitle: String
        get() = when (type) {
            ItemType.Login -> login?.username.orEmpty()
            ItemType.Card -> card?.let { "${it.brand}, *${it.number.takeLast(4)}" }.orEmpty()
            ItemType.Identity -> identity?.fullName.orEmpty()
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
}

@Immutable
data class LoginData(
    val username: String = "",
    val password: String = "",
    val uri: String = "",
    val totpSecret: String? = null,
    /** Further websites and apps (`androidapp://…`, `iosapp://…`) this login belongs to. */
    val additionalUris: List<String> = emptyList(),
) {
    val allUris: List<String> get() = (listOf(uri) + additionalUris).filter { it.isNotBlank() }.distinct()
}

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
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val company: String = "",
)

@Immutable
data class SshKeyData(
    val publicKey: String = "",
    val fingerprint: String = "",
    val privateKey: String = "",
)
