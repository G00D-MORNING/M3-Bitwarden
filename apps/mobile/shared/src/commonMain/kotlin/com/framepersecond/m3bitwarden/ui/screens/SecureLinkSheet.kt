package com.framepersecond.m3bitwarden.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.HideNativeChrome
import com.framepersecond.m3bitwarden.data.SecureLinks
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.platform.shareText
import com.framepersecond.m3bitwarden.ui.LocalCopy
import com.framepersecond.m3bitwarden.ui.components.ConnectedGroup
import com.framepersecond.m3bitwarden.ui.components.GroupOption
import com.framepersecond.m3bitwarden.ui.components.SectionTitle
import com.framepersecond.m3bitwarden.ui.icons.Symbols

/**
 * "Sicheren Link erstellen": the item's fields, encrypted on this device, readable by anyone
 * with the link until it expires or reaches its view limit.
 */
@Composable
fun SecureLinkSheet(item: VaultItem, onClose: () -> Unit) {
    val copy = LocalCopy.current
    var validity by remember { mutableStateOf(SecureLinks.Validity.Day) }
    var views by remember { mutableStateOf(SecureLinks.Views.Once) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val fields = remember(item) { SecureLinks.fields(item) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        HideNativeChrome()
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Sicherer Link", style = MaterialTheme.typography.headlineSmallEmphasized)
            val current = link
            if (current == null) {
                Text(
                    "Wer den Link hat, sieht ${fields.joinToString(", ") { it.first }} von „${item.name}“. " +
                        "Verschlüsselt wird auf diesem Gerät, der Schlüssel steht nur im Link.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionTitle("Gültig für")
                ConnectedGroup(
                    SecureLinks.Validity.entries.map { v -> GroupOption(v.label, selected = validity == v) { validity = v } },
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionTitle("Darf geöffnet werden")
                ConnectedGroup(
                    SecureLinks.Views.entries.map { v -> GroupOption(v.label, selected = views == v) { views = v } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Passwort (optional)") },
                    supportingText = { Text("Teile es auf einem anderen Weg als den Link.") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    enabled = !busy && fields.isNotEmpty(),
                    onClick = {
                        val backend = VaultSession.backend
                        if (backend == null) {
                            error = "Im Demo-Modus nicht verfügbar."
                            return@Button
                        }
                        busy = true
                        error = null
                        backend.createSecureLink(
                            item.name, SecureLinks.content(item), validity.hours, views.count, password.ifEmpty { null },
                        ) { accessId, key, message ->
                            busy = false
                            if (accessId != null && key != null) link = SecureLinks.url(accessId, key) else error = message ?: "Link konnte nicht erstellt werden."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary) else Text("Link erstellen")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Symbols.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "  Gültig ${validity.label.replace("1 ", "einen ").replace("einen Stunde", "eine Stunde")}, " +
                            (if (views.count == 0) "beliebig oft" else if (views.count == 1) "einmal" else "${views.count}-mal") + " zu öffnen" +
                            (if (password.isNotEmpty()) ", mit Passwort." else "."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    current,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(14.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { copy("Link", current, true) }, modifier = Modifier.weight(1f)) {
                        Icon(Symbols.ContentCopy, null)
                        Text("  Kopieren")
                    }
                    Button(onClick = { shareText(current) }, modifier = Modifier.weight(1f)) {
                        Icon(Symbols.Share, null)
                        Text("  Teilen")
                    }
                }
            }
        }
    }
}
