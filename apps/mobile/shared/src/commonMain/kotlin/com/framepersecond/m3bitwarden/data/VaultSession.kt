package com.framepersecond.m3bitwarden.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Connection to a real Bitwarden/Vaultwarden account, implemented per platform with the official
 * Bitwarden SDK (iOS: sdk-swift, Android: sdk-android). All encryption happens there; Kotlin only
 * sees decrypted items while the vault is unlocked.
 */
interface VaultBackend {
    /** The user created ([isNew]) or changed an item in the app or through autofill. */
    fun itemSaved(item: VaultItem, isNew: Boolean)

    /** Moves the item to the trash on the server. */
    fun itemDeleted(id: String)

    /** A new folder; its id starts with [VaultRepository.LOCAL_PREFIX] until the server knows it. */
    fun folderCreated(folder: Folder)

    /** Current code for a TOTP secret or otpauth:// URI, or null if it cannot be computed. */
    fun totpCode(secret: String, epochSeconds: Long): String?

    /** Fetch the latest vault from the server. */
    fun refresh()

    /** Copy to the clipboard; [sensitive] values are hidden from previews and expire. */
    fun toClipboard(text: String, sensitive: Boolean)

    /** Turns unlocking with biometrics on or off (needs an unlocked vault to turn on). */
    fun setBiometricUnlock(enabled: Boolean)

    /** Whether a biometric unlock key is stored on this device. */
    fun biometricUnlockEnabled(): Boolean

    /** Signs out and removes every account trace from this device. */
    fun logout()
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

    /** True while a sync or upload runs. */
    var syncing: Boolean by mutableStateOf(false)

    /** One-off message for the UI (errors from background work); the UI clears it after showing. */
    var message: String? by mutableStateOf(null)

    /** Local id -> server id for items created in this session (so open screens keep working). */
    val idAliases = mutableStateMapOf<String, String>()

    /** Called by the backend once the server assigned an id to a locally created item or folder. */
    fun registerServerId(localId: String, serverId: String) {
        idAliases[localId] = serverId
    }

    /** Server id for an item or folder created locally, or null while it is not uploaded yet. */
    fun serverIdFor(localId: String): String? = idAliases[localId]

    private val lockListeners = mutableListOf<() -> Unit>()

    /** Switches from demo data to a real account. Call once at start, before any UI. */
    fun connect(backend: VaultBackend, email: String, server: String) {
        if (this.backend !== backend) repository.replaceAll(emptyList())
        this.backend = backend
        accountEmail = email
        accountServer = server
    }

    /** Back to "no account" (after logout). */
    fun disconnect() {
        lock()
        backend = null
        accountEmail = ""
        accountServer = ""
        syncStatus = ""
        repository.replaceAll(emptyList())
    }

    /** Shows freshly decrypted items (main thread only). */
    fun loadLive(items: List<VaultItem>, folders: List<Folder>) {
        repository.replaceAll(items, folders)
        isUnlocked = true
    }

    fun markUnlocked() {
        isUnlocked = true
    }

    fun lock() {
        val wasUnlocked = isUnlocked
        isUnlocked = false
        if (backend != null) repository.replaceAll(emptyList())
        if (wasUnlocked || backend != null) lockListeners.forEach { it() }
    }

    fun onLock(listener: () -> Unit) {
        lockListeners += listener
    }
}
