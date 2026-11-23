package com.framepersecond.m3bitwarden.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Connection to a real Bitwarden/Vaultwarden account, implemented per platform (iOS: Swift with
 * the official Bitwarden SDK). All encryption happens there; Kotlin only sees decrypted items
 * while the vault is unlocked.
 */
interface VaultBackend {
    /** The user created ([isNew]) or changed an item in the app or through autofill. */
    fun itemSaved(item: VaultItem, isNew: Boolean)

    fun itemDeleted(id: String)

    /** Current code for a TOTP secret or otpauth:// URI, or null if it cannot be computed. */
    fun totpCode(secret: String, epochSeconds: Long): String?

    /** Fetch the latest vault from the server. */
    fun refresh()

    /** Copy to the clipboard; [sensitive] values are local-only and expire. */
    fun toClipboard(text: String, sensitive: Boolean)
}

/**
 * Process-wide vault state shared by the app UI and the platform autofill entry points
 * (Android AutofillService, iOS credential provider). Only one unlocked vault exists per
 * process; locking clears the autofill caches too, and with a real backend also the items.
 */
object VaultSession {
    val repository: VaultRepository by lazy { VaultRepository() }

    /** Set by the platform when a server account is connected; null means demo data. */
    var backend: VaultBackend? = null
        private set

    var isUnlocked: Boolean by mutableStateOf(false)
        private set

    var accountEmail: String by mutableStateOf("du@example.com")
    var accountServer: String by mutableStateOf("Demo")

    /** Human-readable sync status for the UI, e.g. "Aktualisiert 14:02". */
    var syncStatus: String by mutableStateOf("")

    private val lockListeners = mutableListOf<() -> Unit>()

    /** Switches from demo data to a real account. Call once at start, before any UI. */
    fun connect(backend: VaultBackend, email: String, server: String) {
        this.backend = backend
        accountEmail = email
        accountServer = server
        repository.replaceAll(emptyList())
    }

    /** Shows freshly decrypted items (main thread only). */
    fun loadLive(items: List<VaultItem>) {
        repository.replaceAll(items)
        isUnlocked = true
    }

    fun markUnlocked() {
        isUnlocked = true
    }

    fun lock() {
        isUnlocked = false
        if (backend != null) repository.replaceAll(emptyList())
        lockListeners.forEach { it() }
    }

    fun onLock(listener: () -> Unit) {
        lockListeners += listener
    }
}
