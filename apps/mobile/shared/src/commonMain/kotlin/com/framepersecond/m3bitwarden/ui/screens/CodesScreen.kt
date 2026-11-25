package com.framepersecond.m3bitwarden.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framepersecond.m3bitwarden.AppState
import com.framepersecond.m3bitwarden.HideNativeChrome
import com.framepersecond.m3bitwarden.authenticator.AuthEntry
import com.framepersecond.m3bitwarden.authenticator.ProtonAuthenticator
import com.framepersecond.m3bitwarden.authenticator.ProtonAuthenticator.Status
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.ui.LocalCopy
import com.framepersecond.m3bitwarden.ui.components.ItemTile
import com.framepersecond.m3bitwarden.ui.components.ListRow
import com.framepersecond.m3bitwarden.ui.components.SectionTitle
import com.framepersecond.m3bitwarden.ui.components.WavyRing
import com.framepersecond.m3bitwarden.ui.components.segmentShape
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
private fun epochSeconds(): Long = Clock.System.now().epochSeconds

/**
 * The Proton Authenticator in the app: sign in with Proton once, then every code with its
 * countdown. New codes (and all of them at the first setup) are assigned to logins by hand,
 * with realistic suggestions; an assigned code fills 2FA fields through AutoFill.
 */
@Composable
fun CodesScreen(state: AppState, contentPadding: PaddingValues) {
    val p = ProtonAuthenticator
    var assigning by remember { mutableStateOf(false) }
    // Opening the tab fetches the current codes (new entries from the Proton app show up here).
    LaunchedEffect(Unit) { p.backend?.refresh() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 120.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Codes", style = MaterialTheme.typography.displaySmallEmphasized, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
        when {
            p.backend == null -> InfoCard("Im Demo-Modus nicht verfügbar", "Der Proton Authenticator braucht eine echte Anmeldung.")
            p.status == Status.Disconnected || (p.status == Status.Failed && p.entries.isEmpty() && p.message != null && !p.connected) -> ConnectCard(p.message)
            p.status == Status.SigningIn -> SigningIn()
            p.entries.isEmpty() -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(72.dp)) }
            else -> {
                val open = p.unassigned
                if (open.isNotEmpty()) {
                    ListRow(
                        if (open.size == 1) "1 Code zuordnen" else "${open.size} Codes zuordnen",
                        RoundedCornerShape(24.dp),
                        supporting = "Mit dem passenden Login füllt AutoFill den Code ein",
                        icon = Symbols.Key,
                        iconBg = MaterialTheme.colorScheme.primary,
                        iconTint = MaterialTheme.colorScheme.onPrimary,
                        onClick = { assigning = true },
                    ) { Icon(Symbols.ChevronRight, null) }
                }
                CodeList(p.entries, state)
                p.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (assigning) AssignSheet(state) { assigning = false }
}

@Composable
private fun InfoCard(title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectCard(error: String?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Symbols.VerifiedUser, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        Text("Proton Authenticator verbinden", style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            "Melde dich einmal bei Proton im Browser an. Danach sind deine Codes hier, synchron mit der Proton-App, und füllen 2FA-Felder aus. " +
                "Passwort und Bestätigung gibst du nur bei Proton ein.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { ProtonAuthenticator.backend?.connect() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Mit Proton verbinden") }
    }
}

@Composable
private fun SigningIn() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoadingIndicator(Modifier.size(64.dp))
        Text("Warte auf die Anmeldung bei Proton…", style = MaterialTheme.typography.titleMedium)
        Text("Melde dich im geöffneten Browser an und kehre dann hierher zurück.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { ProtonAuthenticator.backend?.cancelConnect() }) { Text("Abbrechen") }
    }
}

@Composable
private fun CodeList(entries: List<AuthEntry>, state: AppState) {
    val copy = LocalCopy.current
    var now by remember { mutableLongStateOf(epochSeconds()) }
    LaunchedEffect(Unit) { while (true) { now = epochSeconds(); delay(1000) } }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entries.forEachIndexed { i, e ->
            val code = remember(e.uri, now / e.period) { ProtonAuthenticator.backend?.code(e.uri, now) }.orEmpty()
            val left = (e.period - now % e.period).toInt()
            val linked = ProtonAuthenticator.linkedItemId(e)?.let { state.vault.items.firstOrNull { item -> item.id == it } }
            Row(
                Modifier.fillMaxWidth().clip(segmentShape(i, entries.size)).background(MaterialTheme.colorScheme.surface)
                    .clickable { if (code.isNotEmpty()) copy("Code", code, true) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e.issuer.ifBlank { e.name }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        code.chunked(3).joinToString(" "),
                        fontFamily = FontFamily.Monospace, fontSize = 26.sp, letterSpacing = 1.sp,
                        color = if (left <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        linked?.let { "→ ${it.name}" } ?: e.name.substringAfter(':').trim(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                WavyRing(left / e.period.toFloat(), "$left", 44.dp)
            }
        }
    }
}

/** Assign codes one after another: suggestions first, any login on request, or "not now". */
@Composable
private fun AssignSheet(state: AppState, onClose: () -> Unit) {
    val open = ProtonAuthenticator.unassigned
    val entry = open.firstOrNull()
    LaunchedEffect(entry) { if (entry == null) onClose() }
    if (entry == null) return
    var search by remember(entry.id) { mutableStateOf("") }
    var browsing by remember(entry.id) { mutableStateOf(false) }
    val logins = state.vault.items.filter { it.type == ItemType.Login }
    val suggested = remember(entry.id, logins.size) { ProtonAuthenticator.suggestions(entry, logins) }
    ModalBottomSheet(onDismissRequest = onClose) {
        HideNativeChrome()
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Noch ${open.size} zuzuordnen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(entry.issuer.ifBlank { entry.name }, style = MaterialTheme.typography.headlineSmallEmphasized)
            if (entry.name.isNotBlank()) Text(entry.name.substringAfter(':').trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val choices = if (browsing) {
                val q = search.trim().lowercase()
                logins.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.login?.username?.lowercase()?.contains(q) == true || it.login?.allUris?.any { u -> u.lowercase().contains(q) } == true }.take(30)
            } else suggested
            if (!browsing) SectionTitle(if (suggested.isEmpty()) "Kein passender Login gefunden" else "Vorschläge")
            if (browsing) OutlinedTextField(search, { search = it }, label = { Text("Login suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.forEachIndexed { i, item -> LoginChoice(item, segmentShape(i, choices.size)) { ProtonAuthenticator.assign(entry, item) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!browsing) FilledTonalButton(onClick = { browsing = true }) { Text("Anderen Login wählen") }
                TextButton(onClick = { ProtonAuthenticator.skip(entry) }) { Text("Nicht zuordnen") }
            }
        }
    }
}

@Composable
private fun LoginChoice(item: VaultItem, shape: androidx.compose.ui.graphics.Shape, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onPick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemTile(item, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.login?.username?.takeIf(String::isNotBlank), item.login?.allUris?.firstOrNull()?.let(VaultItem::hostOf)).joinToString(" · ") +
                    if (!item.login?.totpSecret.isNullOrBlank()) " · hat schon einen Code" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Symbols.Check, "Zuordnen", tint = MaterialTheme.colorScheme.primary)
    }
}
