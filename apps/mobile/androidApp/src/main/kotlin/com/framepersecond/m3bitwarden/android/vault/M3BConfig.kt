package com.framepersecond.m3bitwarden.android.vault

/** Fixed settings, the same as on iOS (iosApp/Common/M3BConfig.swift). */
object M3BConfig {
    /** Vaultwarden behind Cloudflare: this host only answers devices with a client certificate. */
    const val SERVER_URL = "https://vault.jochenderrochen.de"

    /** Web vault (behind Cloudflare Access), used only for links. */
    const val WEB_VAULT_URL = "https://tresor.jochenderrochen.de"

    const val API_URL = "$SERVER_URL/api"
    const val IDENTITY_URL = "$SERVER_URL/identity"

    /** "mobile" is the OAuth client every Bitwarden phone app uses. */
    const val CLIENT_ID = "mobile"

    /** Bitwarden device type for Android. */
    const val DEVICE_TYPE = 0
    const val CLIENT_VERSION = "2026.11.0"

    val serverHost: String get() = SERVER_URL.removePrefix("https://")
}
