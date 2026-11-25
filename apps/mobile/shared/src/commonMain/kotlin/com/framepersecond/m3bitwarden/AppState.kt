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
import com.framepersecond.m3bitwarden.platform.PlatformPrefs
import kotlinx.coroutines.delay

enum class ThemeMode(val label: String) { System("System"), Light("Hell"), Dark("Dunkel") }

enum class MainTab { Vault, Codes, Generator, Settings }

/** How long the vault stays unlocked while the app is in the background. -1 = until restart. */
enum class VaultTimeout(val label: String, val seconds: Long) {
    Immediately("Sofort", 0),
    OneMinute("Nach 1 Minute", 60),
    FiveMinutes("Nach 5 Minuten", 5 * 60),
    FifteenMinutes("Nach 15 Minuten", 15 * 60),
    ThirtyMinutes("Nach 30 Minuten", 30 * 60),
    OneHour("Nach 1 Stunde", 60 * 60),
    // No "never": the decrypted vault lives only in memory and is always removed again.
}

enum class VaultSort(val label: String) { Name("Name"), Recent("Zuletzt geändert"), Type("Typ") }

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

/**
 * Persistent, non-secret preferences shared by app, autofill and (on iOS) the Swift side, which
 * reads them through `AppSettings.shared`.
 */
object AppSettings {
    var themeMode by mutableStateOf(load("theme") { ThemeMode.valueOf(it) } ?: ThemeMode.System)
        private set
    var dynamicColor by mutableStateOf(load("dynamicColor") { it.toBoolean() } ?: false)
        private set
    var timeout by mutableStateOf(load("timeout") { VaultTimeout.valueOf(it) } ?: VaultTimeout.FiveMinutes)
        private set
    var copyTotpAutomatically by mutableStateOf(load("copyTotp") { it.toBoolean() } ?: true)
        private set
    var clearClipboardSeconds by mutableStateOf(load("clearClipboard") { it.toInt() } ?: 30)
        private set
    var showLogos by mutableStateOf(load("showLogos") { it.toBoolean() } ?: true)
        private set
    var showFavoritesRow by mutableStateOf(load("showFavRow") { it.toBoolean() } ?: true)
        private set
    var sort by mutableStateOf(load("sort") { VaultSort.valueOf(it) } ?: VaultSort.Name)
        private set

    fun updateThemeMode(value: ThemeMode) { themeMode = value; save("theme", value.name) }
    fun updateDynamicColor(value: Boolean) { dynamicColor = value; save("dynamicColor", value.toString()) }
    fun updateTimeout(value: VaultTimeout) { timeout = value; save("timeout", value.name) }
    fun updateCopyTotpAutomatically(value: Boolean) { copyTotpAutomatically = value; save("copyTotp", value.toString()) }
    fun updateClearClipboardSeconds(value: Int) { clearClipboardSeconds = value; save("clearClipboard", value.toString()) }
    fun updateShowLogos(value: Boolean) { showLogos = value; save("showLogos", value.toString()) }
    fun updateShowFavoritesRow(value: Boolean) { showFavoritesRow = value; save("showFavRow", value.toString()) }
    fun updateSort(value: VaultSort) { sort = value; save("sort", value.name) }

    /** For Swift: seconds in background before locking. */
    val timeoutSeconds: Long get() = timeout.seconds

    private fun <T> load(key: String, parse: (String) -> T): T? =
        runCatching { PlatformPrefs.getString(key)?.let(parse) }.getOrNull()

    private fun save(key: String, value: String) = runCatching { PlatformPrefs.putString(key, value) }
}

/** Screens that open on top of the tab bar. */
@Immutable
sealed interface Route {
    data class Detail(val id: String) : Route
    data class Edit(val id: String?, val type: ItemType) : Route
    data object Folders : Route
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

    /** Remembers a value the user actually used (copied or applied). Kept in memory only. */
    fun record(used: String) {
        if (used.isEmpty()) return
        history.remove(used)
        history.add(0, used)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
    }

    fun regenerate() {
        value = when (mode) {
            GeneratorMode.Password -> PasswordGenerator.password(passwordOptions)
            GeneratorMode.Passphrase -> PasswordGenerator.passphrase(passphraseOptions)
        }
    }

    fun clearHistory() = history.clear()

    private companion object {
        const val MAX_HISTORY = 10
    }
}

@Stable
class AppState(val vault: VaultRepository = VaultSession.repository) {
    val settings = AppSettings

    val live: Boolean get() = VaultSession.backend != null

    // Navigation.
    var selectedTab by mutableStateOf(MainTab.Vault)
    val backStack = mutableStateListOf<Route>()

    // State that should survive opening an item and coming back.
    var query by mutableStateOf("")
    var filter by mutableStateOf(VaultFilter.All)
    var folderFilter by mutableStateOf<String?>(null)
    val vaultListState = LazyListState()
    val generator = GeneratorState()

    // Motion.
    /** The user scrolls down: floating bars get out of the way. */
    var chromeCompact by mutableStateOf(false)
    /** Rows that already rose into place in this session (they do not animate again). */
    val seenRows = HashSet<Any>()
    /** Where the open item page came from ("row", "fav"), so its emblem grows out of the right tile. */
    var heroSource by mutableStateOf("row")

    val snackbar = SnackbarHostState()

    /** Demo mode only: there is no real key, any non-empty password opens the sample vault. */
    suspend fun demoUnlock() {
        delay(UNLOCK_DELAY_MS)
        VaultSession.markUnlocked()
    }

    fun lock() {
        reset()
        VaultSession.lock()
    }

    fun reset() {
        backStack.clear()
        selectedTab = MainTab.Vault
        query = ""
        filter = VaultFilter.All
        folderFilter = null
        chromeCompact = false
    }

    /** Opens an item page; [from] names the tile its emblem grows out of. */
    fun openItem(id: String, from: String) {
        heroSource = from
        open(Route.Detail(id))
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
        const val UNLOCK_DELAY_MS = 600L
    }
}
