package com.framepersecond.m3bitwarden

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultRepository
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.generator.PassphraseOptions
import com.framepersecond.m3bitwarden.generator.PasswordGenerator
import com.framepersecond.m3bitwarden.generator.PasswordOptions
import kotlinx.coroutines.delay

enum class ThemeMode { System, Light, Dark }

enum class MainTab { Vault, Generator, Settings }

enum class VaultTimeout(val label: String) {
    Immediately("Sofort"),
    OneMinute("Nach 1 Minute"),
    FiveMinutes("Nach 5 Minuten"),
    FifteenMinutes("Nach 15 Minuten"),
    OnRestart("Beim App-Neustart"),
}

/** Vault list filters, in chip order. */
enum class VaultFilter(val label: String, val type: ItemType?) {
    All("Alle", null),
    Favorites("Favoriten", null),
    Logins("Logins", ItemType.Login),
    Cards("Karten", ItemType.Card),
    Identities("Identitäten", ItemType.Identity),
    Notes("Notizen", ItemType.Note),
    SshKeys("SSH-Schlüssel", ItemType.SshKey),
}

/** Screens that open on top of the tab bar. */
@Immutable
sealed interface Route {
    data class Detail(val id: String) : Route
    data class Edit(val id: String?, val type: ItemType) : Route
}

enum class GeneratorMode { Password, Passphrase }

@Stable
class GeneratorState {
    var mode by mutableStateOf(GeneratorMode.Password)
    var passwordOptions by mutableStateOf(PasswordOptions())
    var passphraseOptions by mutableStateOf(PassphraseOptions())
    var value by mutableStateOf("")
        private set
    val history = mutableStateListOf<String>()

    val entropyBits: Double
        get() = when (mode) {
            GeneratorMode.Password -> PasswordGenerator.entropyBits(passwordOptions)
            GeneratorMode.Passphrase -> PasswordGenerator.entropyBits(passphraseOptions)
        }

    fun regenerate() {
        if (value.isNotEmpty() && history.firstOrNull() != value) {
            history.add(0, value)
            while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        }
        value = when (mode) {
            GeneratorMode.Password -> PasswordGenerator.password(passwordOptions)
            GeneratorMode.Passphrase -> PasswordGenerator.passphrase(passphraseOptions)
        }
    }

    private companion object {
        const val MAX_HISTORY = 5
    }
}

@Stable
class AppState(val vault: VaultRepository = VaultSession.repository) {
    var locked by mutableStateOf(!VaultSession.isUnlocked)
        private set

    // Settings (in-memory for the prototype).
    var themeMode by mutableStateOf(ThemeMode.System)
    var dynamicColor by mutableStateOf(false)
    var biometricUnlock by mutableStateOf(true)
    var timeout by mutableStateOf(VaultTimeout.FiveMinutes)
    var copyTotpAutomatically by mutableStateOf(true)

    val accountEmail: String get() = VaultSession.accountEmail
    val accountServer: String get() = VaultSession.accountServer

    // Navigation.
    var selectedTab by mutableStateOf(MainTab.Vault)
    val backStack = mutableStateListOf<Route>()

    // State that should survive opening an item and coming back.
    var query by mutableStateOf("")
    var filter by mutableStateOf(VaultFilter.All)
    val vaultListState = LazyListState()
    val generator = GeneratorState()

    val snackbar = SnackbarHostState()

    /** Prototype unlock: accepts any non-empty password after a short delay. */
    suspend fun unlock(masterPassword: String): Boolean {
        delay(UNLOCK_DELAY_MS)
        if (masterPassword.isEmpty()) return false
        locked = false
        VaultSession.markUnlocked()
        return true
    }

    suspend fun unlockWithBiometrics() {
        delay(UNLOCK_DELAY_MS)
        locked = false
        VaultSession.markUnlocked()
    }

    fun lock() {
        backStack.clear()
        selectedTab = MainTab.Vault
        query = ""
        filter = VaultFilter.All
        locked = true
        VaultSession.lock()
    }

    fun open(route: Route) {
        backStack.add(route)
    }

    fun back(): Boolean {
        if (backStack.isEmpty()) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Replaces the top screen, e.g. new item editor -> its detail page. */
    fun replaceTop(route: Route) {
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }

    private companion object {
        const val UNLOCK_DELAY_MS = 900L
    }
}
