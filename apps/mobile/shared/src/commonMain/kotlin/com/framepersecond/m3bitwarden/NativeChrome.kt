package com.framepersecond.m3bitwarden

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.framepersecond.m3bitwarden.data.ItemType

/** One button of a native bar: [symbol] is an SF Symbol name. */
data class ChromeAction(val id: String, val label: String, val symbol: String, val enabled: Boolean = true)

/** Everything the native floating controls show at one moment. */
data class ChromeSnapshot(
    /** Selected tab: 0 vault, 1 generator, 2 settings. */
    val tab: Int,
    /** True while the tabs are on screen (no item page on top). */
    val onTabs: Boolean,
    /** The user scrolls down: the bar shrinks to the selected tab. */
    val compact: Boolean,
    /** A sheet or dialog is open in Compose: hide everything so it is not covered. */
    val modal: Boolean,
    /** Item page toolbar (copy, open, …) and its main action (edit). */
    val actions: List<ChromeAction>,
    val primary: ChromeAction?,
    /** Entries of the "new item" menu. */
    val itemTypes: List<ChromeAction>,
) {
    companion object {
        val Hidden = ChromeSnapshot(0, onTabs = false, compact = false, modal = true, actions = emptyList(), primary = null, itemTypes = emptyList())
    }
}

/**
 * Lets a platform draw the app's floating controls natively. On iOS 26 and later the tab bar,
 * the new-item button and the item toolbar are Liquid Glass views in SwiftUI laid over the
 * Compose content; everything else, and all state, stays in Compose.
 *
 * The platform sets [enabled] before the UI starts, listens to [onChange] and reports taps through
 * [selectTab], [createItem] and [runAction]. With [enabled] false (Android, older iOS) Compose draws
 * its own Material versions of these controls.
 */
object NativeChrome {
    var enabled: Boolean = false

    /** Called on the main thread whenever the snapshot changes. */
    var onChange: ((ChromeSnapshot) -> Unit)? = null

    fun selectTab(index: Int) {
        commands?.selectTab?.invoke(MainTab.entries.getOrElse(index) { MainTab.Vault })
    }

    fun createItem(id: String) {
        ItemType.entries.firstOrNull { it.name == id }?.let { commands?.newItem?.invoke(it) }
    }

    fun runAction(id: String) {
        toolbar?.run?.invoke(id)
    }

    internal class Commands(val selectTab: (MainTab) -> Unit, val newItem: (ItemType) -> Unit)

    internal class Toolbar(val owner: Any, val actions: List<ChromeAction>, val primary: ChromeAction?, val run: (String) -> Unit)

    internal var commands: Commands? = null
    internal var modalCount by mutableIntStateOf(0)
    internal var toolbar by mutableStateOf<Toolbar?>(null)

    internal val itemTypes: List<ChromeAction> by lazy {
        ItemType.entries.map { ChromeAction(it.name, it.newLabel, it.sfSymbol) }
    }
}

private val ItemType.newLabel: String
    get() = when (this) {
        ItemType.Login -> "Login"
        ItemType.Card -> "Karte"
        ItemType.Identity -> "Identität"
        ItemType.Note -> "Sichere Notiz"
        ItemType.SshKey -> "SSH-Schlüssel"
    }

private val ItemType.sfSymbol: String
    get() = when (this) {
        ItemType.Login -> "key.fill"
        ItemType.Card -> "creditcard.fill"
        ItemType.Identity -> "person.text.rectangle.fill"
        ItemType.Note -> "note.text"
        ItemType.SshKey -> "terminal.fill"
    }

/** Put inside sheets and dialogs that open over the tabs or an item page. */
@Composable
fun HideNativeChrome() {
    if (!NativeChrome.enabled) return
    DisposableEffect(Unit) {
        NativeChrome.modalCount++
        onDispose { NativeChrome.modalCount-- }
    }
}

/** Shows [actions] in the native item toolbar while the caller is on screen. */
@Composable
internal fun PublishNativeToolbar(actions: List<ChromeAction>, primary: ChromeAction?, run: (String) -> Unit) {
    if (!NativeChrome.enabled) return
    val owner = remember { Any() }
    val current by rememberUpdatedState(run)
    DisposableEffect(actions, primary) {
        NativeChrome.toolbar = NativeChrome.Toolbar(owner, actions, primary) { current(it) }
        onDispose {
            // A newer page may already have published its own toolbar.
            if (NativeChrome.toolbar?.owner === owner) NativeChrome.toolbar = null
        }
    }
}
