package com.framepersecond.m3bitwarden.autofill.match

/** Bitwarden's per-URI match detection, with the same numeric values the server uses. */
enum class UriMatch(val bitwardenValue: Int) {
    Domain(0),
    Host(1),
    StartsWith(2),
    Exact(3),
    RegularExpression(4),
    Never(5),
    ;

    companion object {
        fun fromBitwarden(value: Int?): UriMatch? = entries.firstOrNull { it.bitwardenValue == value }
    }
}

data class LoginUri(val uri: String, val match: UriMatch? = null)

enum class CredentialKind { Login, Card, Identity }

/**
 * What the engine needs to know about a vault item. Metadata only: the password and
 * other secrets stay in the vault and are fetched after the user picked an item.
 */
data class Credential(
    val id: String,
    val name: String,
    val kind: CredentialKind = CredentialKind.Login,
    val username: String = "",
    val uris: List<LoginUri> = emptyList(),
    val favorite: Boolean = false,
    val hasTotp: Boolean = false,
    val lastUsedEpochMs: Long? = null,
    val useCount: Int = 0,
)

enum class AppPlatform { Android, Ios }

/** Where the fill request comes from. */
data class FillTarget(
    val platform: AppPlatform = AppPlatform.Android,
    /** Android package or iOS bundle id of the app showing the form. */
    val appId: String? = null,
    /** SHA-256 of the app's signing certificate, when the platform tells us. */
    val appCertSha256: String? = null,
    /** Human-readable app name, used for link suggestions. */
    val appLabel: String? = null,
    /** True when [appId] is a known browser whose reported web domain we can trust. */
    val isTrustedBrowser: Boolean = false,
    val webDomain: String? = null,
    val webScheme: String? = null,
    /** Full URL when known (iOS gives it, Android usually only the domain). */
    val url: String? = null,
)

/**
 * A link the user confirmed earlier ("use this login in this app"). Stored on the
 * device so it also covers apps Bitwarden itself has no URI for.
 */
data class LearnedLink(
    val credentialId: String,
    val appId: String? = null,
    val appCertSha256: String? = null,
    val domain: String? = null,
)

enum class MatchReason {
    AppId,
    LearnedLink,
    ExactUrl,
    StartsWith,
    Host,
    Domain,
    EquivalentDomain,
    AppVerifiedDomain,
    Regex,

    /** App from the [KnownApps] table or a reverse-domain package name; always confirmed first. */
    KnownApp,
    NameHeuristic,

    /** Cards and identities: offered on any matching form regardless of site. */
    KindOnly,
}

data class Match(
    val credential: Credential,
    /** 0–100: how sure we are this login belongs here. */
    val strength: Int,
    val reason: MatchReason,
    /** Matched on an http page while the item is stored as https, or similar downgrade. */
    val insecureTransport: Boolean = false,
    /**
     * Must not be filled silently: the domain came from an untrusted app's web view or the
     * match is only a name guess. Show it, but ask before filling and before linking.
     */
    val requiresConfirmation: Boolean = false,
)

data class MatchResult(
    /** Safe to offer inline, best first. */
    val primary: List<Match>,
    /** Plausible but unconfirmed, shown under "Vielleicht passend". */
    val possible: List<Match>,
) {
    val best: Match? get() = primary.firstOrNull()
}
