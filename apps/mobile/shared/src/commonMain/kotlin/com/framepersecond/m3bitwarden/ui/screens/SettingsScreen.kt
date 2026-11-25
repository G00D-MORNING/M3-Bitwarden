package com.framepersecond.m3bitwarden.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.AppSettings
import com.framepersecond.m3bitwarden.AppState
import com.framepersecond.m3bitwarden.HideNativeChrome
import com.framepersecond.m3bitwarden.Route
import com.framepersecond.m3bitwarden.ThemeMode
import com.framepersecond.m3bitwarden.VaultTimeout
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.platform.biometricName
import com.framepersecond.m3bitwarden.platform.supportsDynamicColor
import com.framepersecond.m3bitwarden.ui.brands.Brands
import com.framepersecond.m3bitwarden.ui.components.ConnectedGroup
import com.framepersecond.m3bitwarden.ui.components.GroupOption
import com.framepersecond.m3bitwarden.ui.components.ListRow
import com.framepersecond.m3bitwarden.ui.components.SectionTitle
import com.framepersecond.m3bitwarden.ui.components.SpinningShape
import com.framepersecond.m3bitwarden.ui.components.SwitchRow
import com.framepersecond.m3bitwarden.ui.components.segmentShape
import com.framepersecond.m3bitwarden.ui.icons.Symbols

const val APP_VERSION = "1.0.0"

/** Rows the platform adds to the "Automatisch ausfüllen" section (Android: service status). */
typealias PlatformSettings = @Composable () -> Unit

@Composable
fun SettingsScreen(state: AppState, contentPadding: PaddingValues, platformSettings: PlatformSettings) {
    val settings = AppSettings
    val backend = VaultSession.backend
    var choose by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var biometric by remember { mutableStateOf(backend?.biometricUnlockEnabled() ?: true) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 120.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Einstellungen", style = MaterialTheme.typography.displaySmallEmphasized, modifier = Modifier.padding(start = 4.dp, top = 12.dp))

        // Account card
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpinningShape(MaterialShapes.Cookie12Sided, MaterialTheme.colorScheme.primary, 64.dp) {
                Text(VaultSession.accountEmail.firstOrNull()?.uppercase() ?: "A", style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(VaultSession.accountEmail.ifEmpty { "Demo" }, style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(VaultSession.accountServer.ifEmpty { "Beispieldaten" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            FilledIconButton(onClick = { state.lock() }, modifier = Modifier.size(48.dp)) { Icon(Symbols.Lock, "Tresor sperren") }
        }

        Section("Server und Tresor") {
            val rows = buildList<@Composable (androidx.compose.ui.graphics.Shape) -> Unit> {
                if (backend != null) {
                    add { s -> ListRow("Jetzt synchronisieren", s, supporting = VaultSession.syncStatus.ifEmpty { "Holt den aktuellen Stand vom Server" }, icon = Symbols.Sync, onClick = { backend.refresh() }) }
                    add { s -> ListRow("Server", s, supporting = "${VaultSession.accountServer} · mit Gerätezertifikat", icon = Symbols.Language) }
                    add { s -> ListRow("Verschlüsselung", s, supporting = "Bitwarden-SDK, Ende-zu-Ende", icon = Symbols.VerifiedUser) }
                }
                add { s -> ListRow("Ordner", s, supporting = "${state.vault.folders.size} Ordner", icon = Symbols.Folder, onClick = { state.open(Route.Folders) }) { Icon(Symbols.ChevronRight, null) } }
            }
            rows.forEachIndexed { i, r -> r(segmentShape(i, rows.size)) }
        }

        Section("Sicherheit") {
            SwitchRow("Mit $biometricName entsperren", biometric, segmentShape(0, 3), supporting = "Statt Master-Passwort", icon = Symbols.Fingerprint, enabled = backend != null) {
                biometric = it
                backend?.setBiometricUnlock(it)
            }
            ListRow("Tresor-Timeout", segmentShape(1, 3), supporting = settings.timeout.label, icon = Symbols.LockClock, onClick = { choose = "timeout" }) { Icon(Symbols.ChevronRight, null) }
            ListRow("Zwischenablage leeren", segmentShape(2, 3), supporting = clipboardLabel(settings.clearClipboardSeconds), icon = Symbols.ContentCopy, onClick = { choose = "clipboard" }) { Icon(Symbols.ChevronRight, null) }
        }

        Section("Proton Authenticator") {
            val proton = com.framepersecond.m3bitwarden.authenticator.ProtonAuthenticator
            if (proton.connected) {
                ListRow("Verbunden", segmentShape(0, 2), supporting = "${proton.entries.size} Codes · bleibt angemeldet", icon = Symbols.Timer, onClick = { state.selectedTab = com.framepersecond.m3bitwarden.MainTab.Codes })
                ListRow("Proton trennen", segmentShape(1, 2), supporting = "Meldet diese App bei Proton ab", icon = Symbols.Logout, onClick = { proton.backend?.disconnect() })
            } else {
                ListRow("Mit Proton verbinden", segmentShape(0, 1), supporting = "Codes aus deinem Proton Authenticator", icon = Symbols.Timer, onClick = { state.selectedTab = com.framepersecond.m3bitwarden.MainTab.Codes })
            }
        }

        Section("Automatisch ausfüllen") {
            SwitchRow("Bestätigungscode kopieren", settings.copyTotpAutomatically, segmentShape(0, 1), supporting = "Nach dem Ausfüllen", icon = Symbols.Timer) {
                settings.updateCopyTotpAutomatically(it)
            }
            platformSettings()
        }

        Section("Darstellung") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Design", style = MaterialTheme.typography.bodyLarge)
                ConnectedGroup(
                    ThemeMode.entries.map { mode ->
                        GroupOption(
                            mode.label,
                            when (mode) {
                                ThemeMode.System -> Symbols.Settings
                                ThemeMode.Light -> Symbols.Palette
                                ThemeMode.Dark -> Symbols.DarkMode
                            },
                            settings.themeMode == mode,
                        ) { settings.updateThemeMode(mode) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SwitchRow(
                "Dynamische Farben",
                settings.dynamicColor && supportsDynamicColor,
                segmentShape(1, 2),
                supporting = if (supportsDynamicColor) "Farben aus deinem Hintergrundbild" else "Auf diesem Gerät nicht verfügbar",
                icon = Symbols.Palette,
                enabled = supportsDynamicColor,
            ) { settings.updateDynamicColor(it) }
        }

        Section("Website-Symbole") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Brands.all.take(7).forEach { b ->
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(b.bg)), contentAlignment = Alignment.Center) {
                            Icon(Brands.icon(b), null, tint = Color(b.fg), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Text("Website-Symbole kommen über den Icon-Dienst deines Vaultwarden (das Gerät spricht nur mit deinem Server) und liegen nur im Arbeitsspeicher. ${Brands.all.size} Markenlogos sind zusätzlich in der App enthalten.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SwitchRow("Logos anzeigen", settings.showLogos, segmentShape(1, 2), supporting = "Statt Anfangsbuchstaben", icon = Symbols.Apps) { settings.updateShowLogos(it) }
        }

        Section("Info") {
            ListRow("Version", segmentShape(0, 1), supporting = "M3 Bitwarden $APP_VERSION · Bitwarden SDK", icon = Symbols.Info)
        }

        if (backend != null) {
            OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Symbols.Logout, null)
                Text("Abmelden", Modifier.padding(start = 8.dp))
            }
        }
    }

    when (choose) {
        "timeout" -> ChoiceDialog("Tresor-Timeout", "Wann soll der Tresor automatisch gesperrt werden?", Symbols.LockClock, VaultTimeout.entries.map { it.label }, settings.timeout.ordinal, { choose = null }) {
            settings.updateTimeout(VaultTimeout.entries[it])
        }
        "clipboard" -> {
            val options = listOf(30, 60, 90, 120, 300, 0)
            ChoiceDialog("Zwischenablage leeren", "Kopierte Passwörter werden danach gelöscht.", Symbols.ContentCopy, options.map(::clipboardLabel), options.indexOf(settings.clearClipboardSeconds).coerceAtLeast(0), { choose = null }) {
                settings.updateClearClipboardSeconds(options[it])
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            icon = { HideNativeChrome(); Icon(Symbols.Logout, null) },
            title = { Text("Abmelden?") },
            text = { Text("Konto, Schlüssel und Offline-Kopie des Tresors werden von diesem Gerät entfernt. Das Gerätezertifikat bleibt erhalten.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    state.reset()
                    backend?.logout()
                }) { Text("Abmelden") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

private fun clipboardLabel(seconds: Int): String = when {
    seconds <= 0 -> "Nie"
    seconds < 60 -> "Nach $seconds Sekunden"
    seconds == 60 -> "Nach 1 Minute"
    seconds % 60 == 0 -> "Nach ${seconds / 60} Minuten"
    else -> "Nach $seconds Sekunden"
}

@Composable
private fun ChoiceDialog(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, options: List<String>, selected: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { HideNativeChrome(); Icon(icon, null) },
        title = { Text(title) },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(i); onDismiss() }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = { onSelect(i); onDismiss() })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}
