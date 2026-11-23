package com.framepersecond.m3bitwarden

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.autofill.AutofillVault
import com.framepersecond.m3bitwarden.autofill.InMemoryAutofillStore
import com.framepersecond.m3bitwarden.autofill.ValueKind
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.ui.components.ItemAvatar
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import com.framepersecond.m3bitwarden.ui.theme.M3BitwardenTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Temporary shell until the approved prototype screens are implemented: a searchable list
 * with copy actions. It exists so the vault and autofill can be used on a device today.
 *
 * With a connected backend, locking and unlocking are handled by the platform (iOS: Face ID /
 * master password in SwiftUI); without one, a demo unlock button is shown.
 *
 * @param extra platform content shown above the list (Android: the autofill setup card).
 */
@Composable
fun App(extra: @Composable () -> Unit = {}) {
    val state = remember { AppState() }
    val scope = rememberCoroutineScope()
    val live = VaultSession.backend != null
    M3BitwardenTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            if (!VaultSession.isUnlocked) {
                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Symbols.ShieldLockFill, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Tresor gesperrt", style = MaterialTheme.typography.headlineMediumEmphasized)
                        if (!live) Button(onClick = { scope.launch { state.unlockWithBiometrics() } }) { Text("Entsperren") }
                    }
                }
            } else {
                VaultList(extra = extra, live = live, onLock = { state.lock() })
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun VaultList(extra: @Composable () -> Unit, live: Boolean, onLock: () -> Unit) {
    val items = VaultSession.repository.items
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(Clock.System.now().epochSeconds) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Clock.System.now().epochSeconds
        }
    }
    val values = remember { AutofillVault(InMemoryAutofillStore()) }
    val clipboard = LocalClipboardManager.current
    val copy: (String, Boolean) -> Unit = { text, sensitive ->
        VaultSession.backend?.toClipboard(text, sensitive) ?: clipboard.setText(AnnotatedString(text))
    }
    val shown = items
        .filter { query.isBlank() || it.name.contains(query, true) || it.subtitle.contains(query, true) || it.login?.allUris.orEmpty().any { u -> u.contains(query, true) } }
        .sortedWith(compareByDescending<VaultItem> { it.favorite }.thenBy { it.name.lowercase() })

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tresor", style = MaterialTheme.typography.headlineLargeEmphasized)
                    val status = listOf(VaultSession.accountEmail, VaultSession.syncStatus).filter { it.isNotBlank() }.joinToString("  ·  ")
                    if (live) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (live) TextButton(onClick = { VaultSession.backend?.refresh() }) { Text("Aktualisieren") }
                TextButton(onClick = onLock) { Text("Sperren") }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Suchen") },
            )
        }
        item { extra() }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (items.isEmpty()) "Noch keine Einträge. Lege welche in der Weboberfläche an oder speichere sie beim Anmelden." else "Nichts gefunden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(shown, key = { it.id }) { item ->
            Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == item.id) null else item.id }.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ItemAvatar(type = item.type, name = item.name)
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                AnimatedVisibility(expanded == item.id) {
                    Row(Modifier.padding(top = 8.dp, start = 54.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CopyActions(item, now, values, copy)
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyActions(item: VaultItem, now: Long, values: AutofillVault, copy: (String, Boolean) -> Unit) {
    when (item.type) {
        ItemType.Login -> {
            item.login?.username?.takeIf { it.isNotEmpty() }?.let { FilledTonalButton(onClick = { copy(it, false) }) { Text("Benutzer") } }
            item.login?.password?.takeIf { it.isNotEmpty() }?.let { FilledTonalButton(onClick = { copy(it, true) }) { Text("Passwort") } }
            values.value(item.id, ValueKind.Totp, now)?.let { code ->
                FilledTonalButton(onClick = { copy(code, true) }) { Text("$code  ·  ${30 - now % 30}s") }
            }
        }
        ItemType.Card -> {
            item.card?.number?.takeIf { it.isNotEmpty() }?.let { FilledTonalButton(onClick = { copy(it, true) }) { Text("Nummer") } }
            item.card?.code?.takeIf { it.isNotEmpty() }?.let { FilledTonalButton(onClick = { copy(it, true) }) { Text("Prüfnummer") } }
        }
        ItemType.Note -> item.notes.takeIf { it.isNotEmpty() }?.let { FilledTonalButton(onClick = { copy(it, true) }) { Text("Notiz") } }
        ItemType.Identity -> item.identity?.email?.takeIf { it.isNotEmpty() }?.let { FilledTonalButton(onClick = { copy(it, false) }) { Text("E-Mail") } }
        ItemType.SshKey -> item.sshKey?.publicKey?.takeIf { it.isNotEmpty() }?.let { FilledTonalButton(onClick = { copy(it, false) }) { Text("Öffentlicher Schlüssel") } }
    }
}
