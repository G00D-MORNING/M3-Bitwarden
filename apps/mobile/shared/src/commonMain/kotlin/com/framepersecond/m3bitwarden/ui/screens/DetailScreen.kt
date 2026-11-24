package com.framepersecond.m3bitwarden.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import com.framepersecond.m3bitwarden.ChromeAction
import com.framepersecond.m3bitwarden.HideNativeChrome
import com.framepersecond.m3bitwarden.NativeChrome
import com.framepersecond.m3bitwarden.PublishNativeToolbar
import com.framepersecond.m3bitwarden.ui.CopyButton
import com.framepersecond.m3bitwarden.ui.RollingText
import com.framepersecond.m3bitwarden.ui.hero
import com.framepersecond.m3bitwarden.ui.revealText
import com.framepersecond.m3bitwarden.ui.components.colorize
import com.framepersecond.m3bitwarden.ui.theme.PasswordColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framepersecond.m3bitwarden.AppSettings
import com.framepersecond.m3bitwarden.AppState
import com.framepersecond.m3bitwarden.Route
import com.framepersecond.m3bitwarden.data.CustomFieldType
import com.framepersecond.m3bitwarden.data.DemoTotp
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.generator.PasswordGenerator
import com.framepersecond.m3bitwarden.platform.openUrl
import com.framepersecond.m3bitwarden.security.SecurityCheck
import com.framepersecond.m3bitwarden.ui.LocalCopy
import com.framepersecond.m3bitwarden.ui.brands.Brands
import com.framepersecond.m3bitwarden.ui.components.ConnectedGroup
import com.framepersecond.m3bitwarden.ui.components.GroupOption
import com.framepersecond.m3bitwarden.ui.components.SpinningShape
import com.framepersecond.m3bitwarden.ui.components.WavyRing
import com.framepersecond.m3bitwarden.ui.components.WavyStrength
import com.framepersecond.m3bitwarden.ui.components.colorizedPassword
import com.framepersecond.m3bitwarden.ui.components.containerColor
import com.framepersecond.m3bitwarden.ui.components.contentColor
import com.framepersecond.m3bitwarden.ui.components.icon
import com.framepersecond.m3bitwarden.ui.components.polygon
import com.framepersecond.m3bitwarden.ui.components.rememberEpochSeconds
import com.framepersecond.m3bitwarden.ui.components.segmentShape
import com.framepersecond.m3bitwarden.ui.icons.Symbols

/** Current code for a TOTP secret: SDK when connected, demo generator for sample data. */
fun totpCode(secret: String, now: Long): String? {
    val backend = VaultSession.backend ?: return DemoTotp.code(secret, now)
    return backend.totpCode(secret, now)
}

/** Period of an otpauth:// URI (default 30 s). */
fun totpPeriod(secret: String): Int =
    Regex("[?&]period=(\\d+)").find(secret)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..300 } ?: 30

val ItemType.short: String
    get() = when (this) {
        ItemType.Login -> "Login"
        ItemType.Card -> "Karte"
        ItemType.Identity -> "Identität"
        ItemType.Note -> "Notiz"
        ItemType.SshKey -> "SSH"
    }

/** One row of the connected field list. */
private data class DetailField(
    val label: String,
    val value: String,
    val secret: Boolean = false,
    val password: Boolean = false,
    val mono: Boolean = false,
    val url: String? = null,
    val copyLabel: String? = label,
)

private fun fieldsOf(item: VaultItem): List<DetailField> = buildList {
    when (item.type) {
        ItemType.Login -> item.login?.let { l ->
            if (l.username.isNotEmpty()) add(DetailField("Benutzername", l.username))
            if (l.password.isNotEmpty()) add(DetailField("Passwort", l.password, secret = true, password = true))
            l.allUris.forEachIndexed { i, uri ->
                val web = uri.startsWith("http://") || uri.startsWith("https://") || ('.' in uri && "://" !in uri)
                val label = when {
                    uri.startsWith("androidapp://") -> "Android-App"
                    uri.startsWith("iosapp://") -> "iOS-App"
                    l.allUris.size > 1 -> "Website ${i + 1}"
                    else -> "Website"
                }
                add(DetailField(label, uri, url = if (web) (if ("://" in uri) uri else "https://$uri") else null, copyLabel = "Adresse"))
            }
            if (l.passkeyDetails.isNotEmpty()) {
                l.passkeyDetails.forEach { p ->
                    val value = listOf(p.site, p.userName).filter { it.isNotBlank() }.joinToString(" · ")
                    add(DetailField(if (p.createdLabel.isBlank()) "Passkey" else "Passkey · seit ${p.createdLabel}", value, copyLabel = null))
                }
            } else if (l.passkeys > 0) {
                add(DetailField("Passkey", if (l.passkeys == 1) "1 Passkey gespeichert" else "${l.passkeys} Passkeys gespeichert", copyLabel = null))
            }
        }
        ItemType.Card -> item.card?.let { c ->
            if (c.cardholder.isNotEmpty()) add(DetailField("Karteninhaber", c.cardholder))
            if (c.number.isNotEmpty()) add(DetailField("Kartennummer", c.number.chunked(4).joinToString(" "), secret = true, mono = true))
            if (c.expMonth.isNotEmpty() || c.expYear.isNotEmpty()) add(DetailField("Gültig bis", "${c.expMonth.padStart(2, '0')}/${c.expYear}", copyLabel = "Ablaufdatum"))
            if (c.code.isNotEmpty()) add(DetailField("Sicherheitscode", c.code, secret = true, mono = true))
            if (c.brand.isNotEmpty()) add(DetailField("Marke", c.brand, copyLabel = null))
        }
        ItemType.Identity -> item.identity?.let { i ->
            listOf(
                "Name" to i.fullName, "Benutzername" to i.username, "Firma" to i.company, "E-Mail" to i.email,
                "Telefon" to i.phone, "Adresse" to i.address,
            ).filter { it.second.isNotBlank() }.forEach { add(DetailField(it.first, it.second)) }
            listOf("Sozialversicherungsnummer" to i.ssn, "Reisepassnummer" to i.passportNumber, "Führerscheinnummer" to i.licenseNumber)
                .filter { it.second.isNotBlank() }.forEach { add(DetailField(it.first, it.second, secret = true)) }
        }
        ItemType.SshKey -> item.sshKey?.let { k ->
            if (k.publicKey.isNotEmpty()) add(DetailField("Öffentlicher Schlüssel", k.publicKey, mono = true))
            if (k.fingerprint.isNotEmpty()) add(DetailField("Fingerabdruck", k.fingerprint, mono = true))
            if (k.privateKey.isNotEmpty()) add(DetailField("Privater Schlüssel", k.privateKey, secret = true, mono = true))
        }
        ItemType.Note -> Unit
    }
    if (item.notes.isNotEmpty()) add(DetailField(if (item.type == ItemType.Note) "Notiz" else "Notizen", item.notes))
    item.fields.forEach { f ->
        when (f.type) {
            CustomFieldType.Boolean -> add(DetailField(f.name, if (f.value.toBooleanStrictOrNull() == true) "Ja" else "Nein", copyLabel = null))
            CustomFieldType.Linked -> add(DetailField(f.name, "Verknüpftes Feld", copyLabel = null))
            else -> add(DetailField(f.name.ifEmpty { "Feld" }, f.value, secret = f.type == CustomFieldType.Hidden))
        }
    }
    if (isEmpty()) add(DetailField("Leer", "Dieser Eintrag hat noch keine Felder.", copyLabel = null))
}

@Composable
fun DetailScreen(state: AppState, id: String) {
    val item = state.vault.get(id)
    var menu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    val copy = LocalCopy.current

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (item == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Eintrag nicht mehr vorhanden", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { state.back() }) { Text("Zurück") }
            }
            return@Box
        }
        val finding = remember(state.vault.items.toList(), id) { SecurityCheck.run(state.vault.items).forItem(item.id) }

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.back() }) { Icon(Symbols.ArrowBack, "Zurück") }
                Spacer(Modifier.weight(1f))
                if (item.editable) {
                    IconButton(onClick = { state.open(Route.Edit(item.id, item.type)) }) { Icon(Symbols.Edit, "Bearbeiten") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Symbols.MoreVert, "Weitere Aktionen") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            item.login?.allUris?.firstOrNull { it.startsWith("http") }?.let { url ->
                                DropdownMenuItem(text = { Text("Website öffnen") }, leadingIcon = { Icon(Symbols.OpenInNew, null) }, onClick = { menu = false; openUrl(url) })
                            }
                            DropdownMenuItem(text = { Text("In Ordner verschieben") }, leadingIcon = { Icon(Symbols.Folder, null) }, onClick = { menu = false; folderMenu = true })
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("In den Papierkorb", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Symbols.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                        FolderMenu(state, item, folderMenu) { folderMenu = false }
                    }
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(item, state) { folderMenu = true }

                item.login?.totpSecret?.takeIf { it.isNotBlank() }?.let { secret -> TotpCard(secret) { code -> copy("Code", code, true) } }

                if (finding != null) {
                    val main = finding.issues.firstOrNull { it.serious } ?: finding.issues.first()
                    val bg = if (main.serious) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                    val fg = if (main.serious) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (main.serious) Symbols.Warning else Symbols.Passkey, null, tint = fg)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(finding.issues.joinToString(" · ") { it.kind.short }, style = MaterialTheme.typography.titleSmallEmphasized, color = fg)
                            finding.issues.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium, color = fg) }
                            if (main.serious && item.editable) {
                                Button(onClick = { state.open(Route.Edit(item.id, item.type)) }, modifier = Modifier.padding(top = 6.dp)) {
                                    Icon(Symbols.Autorenew, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Neues Passwort")
                                }
                            }
                        }
                    }
                }

                val fields = remember(item) { fieldsOf(item) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    fields.forEachIndexed { i, f -> FieldRow(f, segmentShape(i, fields.size)) }
                }

                if (item.passwordHistory.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val n = if (historyOpen) item.passwordHistory.size + 1 else 1
                        Row(
                            Modifier.fillMaxWidth().clip(segmentShape(0, n)).background(MaterialTheme.colorScheme.surface)
                                .defaultMinSize(minHeight = 56.dp).padding(start = 20.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Symbols.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Passwortverlauf (${item.passwordHistory.size})", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = { historyOpen = !historyOpen }) { Text(if (historyOpen) "Ausblenden" else "Anzeigen") }
                        }
                        if (historyOpen) {
                            item.passwordHistory.forEachIndexed { i, h ->
                                FieldRow(DetailField(h.dateLabel, h.password, secret = true, password = true, copyLabel = "Altes Passwort"), segmentShape(i + 1, n))
                            }
                        }
                    }
                }

                Text(
                    listOfNotNull(item.updatedLabel.takeIf { it.isNotEmpty() }?.let { "Geändert $it" }, if (item.shared) "Geteilt über Organisation" else null)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Toolbar(item, state, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
    }

    if (confirmDelete && item != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Symbols.Delete, null) },
            title = { Text("Eintrag löschen?") },
            text = {
                HideNativeChrome()
                Text("„${item.name}“ wird in den Papierkorb verschoben und lässt sich in der Weboberfläche 30 Tage lang wiederherstellen.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    state.vault.delete(item.id)
                    state.back()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun FolderMenu(state: AppState, item: VaultItem, open: Boolean, onClose: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        DropdownMenuItem(
            text = { Text("Kein Ordner") },
            trailingIcon = { if (item.folderId == null) Icon(Symbols.Check, null) },
            onClick = { onClose(); if (item.folderId != null) state.vault.save(item.copy(folderId = null)) },
        )
        state.vault.folders.forEach { folder ->
            DropdownMenuItem(
                text = { Text(folder.name) },
                leadingIcon = { Icon(Symbols.Folder, null) },
                trailingIcon = { if (item.folderId == folder.id) Icon(Symbols.Check, null) },
                onClick = { onClose(); if (item.folderId != folder.id) state.vault.save(item.copy(folderId = folder.id)) },
            )
        }
    }
}

@Composable
private fun Header(item: VaultItem, state: AppState, onFolder: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val brand = if (AppSettings.showLogos) Brands.forItem(item) else null
        SpinningShape(item.type.polygon, item.type.containerColor, 128.dp, modifier = Modifier.hero("${state.heroSource}-tile-${item.id}")) {
            if (brand != null) {
                Box(
                    Modifier.size(72.dp).shadow(6.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color(brand.bg)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Brands.icon(brand), null, tint = Color(brand.fg), modifier = Modifier.size(38.dp)) }
            } else {
                Text(item.name.trim().firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.displayMediumEmphasized, color = item.type.contentColor)
            }
        }
        Text(item.name, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center, modifier = Modifier.hero("${state.heroSource}-name-${item.id}"))
        ConnectedGroup(
            listOf(
                GroupOption(item.type.short, item.type.icon, false) { if (item.editable) state.open(Route.Edit(item.id, item.type)) },
                GroupOption("Favorit", if (item.favorite) Symbols.StarFill else Symbols.Star, item.favorite) { if (item.editable) state.vault.toggleFavorite(item.id) },
                GroupOption(item.folder ?: "Ordner", Symbols.Folder, false) { if (item.editable) onFolder() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TotpCard(secret: String, onCopy: (String) -> Unit) {
    val now = rememberEpochSeconds()
    val period = totpPeriod(secret)
    val code = remember(secret, now / period) { totpCode(secret, now) }
    val left = period - (now % period).toInt()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Bestätigungscode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (code == null) {
                Text("Ungültiger Schlüssel", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            } else {
                RollingText(
                    if (code.length == 6) code.take(3) + " " + code.drop(3) else code,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        WavyRing(left / period.toFloat(), "$left", 48.dp, color = if (left <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, textColor = MaterialTheme.colorScheme.onPrimaryContainer)
        FilledIconButton(onClick = { code?.let(onCopy) }, enabled = code != null, modifier = Modifier.size(48.dp)) { Icon(Symbols.ContentCopy, "Code kopieren") }
    }
}

@Composable
private fun FieldRow(f: DetailField, shape: Shape) {
    val copy = LocalCopy.current
    var revealed by remember(f.value) { mutableStateOf(false) }
    // Reveal sweeps over the value from left to right instead of swapping it at once.
    val shown by animateIntAsState(if (revealed) f.value.length else 0, tween((f.value.length * 18).coerceIn(160, 420), easing = LinearEasing))
    val digit = PasswordColors.digit
    val symbol = PasswordColors.symbol
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface).defaultMinSize(minHeight = 64.dp).padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                f.secret && !revealed && shown == 0 -> Text("•".repeat(f.value.length.coerceIn(8, 14)), style = MaterialTheme.typography.bodyLarge)
                f.secret && shown < f.value.length -> Text(
                    revealText(f.value, shown) { if (f.password) colorize(it, digit, symbol) else AnnotatedString(it) },
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (f.password || f.mono) FontFamily.Monospace else null),
                )
                f.password -> Text(colorizedPassword(f.value), fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 24.sp)
                else -> Text(f.value, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (f.mono) FontFamily.Monospace else null))
            }
            if (f.password) WavyStrength(PasswordGenerator.estimateBits(f.value), Modifier.padding(top = 6.dp, end = 12.dp))
        }
        if (f.secret) {
            IconToggleButton(checked = revealed, onCheckedChange = { revealed = it }, shapes = IconButtonDefaults.toggleableShapes()) {
                AnimatedContent(revealed, transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f), initialScale = 0.5f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut()) }, label = "eye") { r ->
                    Icon(if (r) Symbols.VisibilityOff else Symbols.Visibility, if (r) "Verbergen" else "Anzeigen")
                }
            }
        }
        if (f.url != null) IconButton(onClick = { openUrl(f.url) }) { Icon(Symbols.OpenInNew, "Website öffnen") }
        if (f.copyLabel != null) {
            CopyButton("${f.copyLabel} kopieren", { copy(f.copyLabel, if (f.label == "Kartennummer") f.value.replace(" ", "") else f.value, f.secret) })
        }
    }
}

private data class ToolAction(val icon: ImageVector, val symbol: String, val label: String, val enabled: Boolean, val run: () -> Unit)

@Composable
private fun Toolbar(item: VaultItem, state: AppState, modifier: Modifier) {
    val copy = LocalCopy.current
    val actions = buildList {
        when (item.type) {
            ItemType.Login -> {
                val l = item.login
                add(ToolAction(Symbols.Person, "person.fill", "Benutzername kopieren", !l?.username.isNullOrEmpty()) { copy("Benutzername", l!!.username, false) })
                add(ToolAction(Symbols.Key, "key.fill", "Passwort kopieren", !l?.password.isNullOrEmpty()) { copy("Passwort", l!!.password, true) })
                l?.allUris?.firstOrNull { it.startsWith("http") }?.let { url -> add(ToolAction(Symbols.OpenInNew, "safari.fill", "Website öffnen", true) { openUrl(url) }) }
            }
            ItemType.Card -> {
                val c = item.card
                add(ToolAction(Symbols.CreditCard, "creditcard.fill", "Kartennummer kopieren", !c?.number.isNullOrEmpty()) { copy("Kartennummer", c!!.number, true) })
                add(ToolAction(Symbols.Pin, "number", "Sicherheitscode kopieren", !c?.code.isNullOrEmpty()) { copy("Sicherheitscode", c!!.code, true) })
            }
            ItemType.SshKey -> add(ToolAction(Symbols.KeyVertical, "key.horizontal.fill", "Öffentlichen Schlüssel kopieren", !item.sshKey?.publicKey.isNullOrEmpty()) { copy("Öffentlicher Schlüssel", item.sshKey!!.publicKey, false) })
            ItemType.Identity -> {
                val i = item.identity
                add(ToolAction(Symbols.Mail, "envelope.fill", "E-Mail kopieren", !i?.email.isNullOrEmpty()) { copy("E-Mail", i!!.email, false) })
                add(ToolAction(Symbols.Home, "house.fill", "Adresse kopieren", !i?.address.isNullOrEmpty()) { copy("Adresse", i!!.address, false) })
            }
            ItemType.Note -> add(ToolAction(Symbols.ContentCopy, "doc.on.doc.fill", "Inhalt kopieren", item.notes.isNotEmpty()) { copy("Notiz", item.notes, true) })
        }
    }
    val edit = { if (item.editable) state.open(Route.Edit(item.id, item.type)) }

    if (NativeChrome.enabled) {
        // iOS 26+: drawn as a Liquid Glass toolbar by the app.
        PublishNativeToolbar(
            actions = actions.mapIndexed { i, a -> ChromeAction("a$i", a.label, a.symbol, a.enabled) },
            primary = if (item.editable) ChromeAction("edit", "Bearbeiten", "pencil") else null,
        ) { id -> if (id == "edit") edit() else actions.getOrNull(id.drop(1).toIntOrNull() ?: -1)?.run?.invoke() }
        return
    }

    // Rises from the bottom edge when the page opens.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        shown,
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 380f)) { it * 2 } + fadeIn(tween(200, delayMillis = 80)),
        modifier = modifier,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            floatingActionButton = {
                FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = edit) {
                    Icon(Symbols.Edit, "Bearbeiten")
                }
            },
        ) {
            actions.forEach { a ->
                IconButton(onClick = a.run, enabled = a.enabled, shapes = IconButtonDefaults.shapes(), colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                    Icon(a.icon, a.label)
                }
            }
        }
    }
}
