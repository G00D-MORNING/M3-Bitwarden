package com.framepersecond.m3bitwarden.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framepersecond.m3bitwarden.AppSettings
import com.framepersecond.m3bitwarden.AppState
import com.framepersecond.m3bitwarden.Route
import com.framepersecond.m3bitwarden.data.CustomField
import com.framepersecond.m3bitwarden.data.CustomFieldType
import com.framepersecond.m3bitwarden.data.IdentityData
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.generator.PasswordGenerator
import com.framepersecond.m3bitwarden.ui.LocalCopy
import com.framepersecond.m3bitwarden.ui.brands.Brands
import com.framepersecond.m3bitwarden.ui.components.BoxSection
import com.framepersecond.m3bitwarden.ui.components.ConnectedGroup
import com.framepersecond.m3bitwarden.ui.components.GroupOption
import com.framepersecond.m3bitwarden.ui.components.ListRow
import com.framepersecond.m3bitwarden.ui.components.SectionTitle
import com.framepersecond.m3bitwarden.ui.components.SpinningShape
import com.framepersecond.m3bitwarden.ui.components.WavyStrength
import com.framepersecond.m3bitwarden.ui.components.containerColor
import com.framepersecond.m3bitwarden.ui.components.contentColor
import com.framepersecond.m3bitwarden.ui.components.icon
import com.framepersecond.m3bitwarden.ui.components.label
import com.framepersecond.m3bitwarden.ui.components.polygon
import com.framepersecond.m3bitwarden.ui.components.segmentShape
import com.framepersecond.m3bitwarden.ui.icons.Symbols

/**
 * Filled M3 Expressive text field as in the prototype: tonal box, rounder and outlined in
 * primary while focused.
 */
@Composable
fun TextInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    secret: Boolean = false,
    monospace: Boolean = false,
    capitalize: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    supporting: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val radius = if (!singleLine) 20.dp else if (focused) 24.dp else 16.dp
    val shape = RoundedCornerShape(radius)
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        leadingIcon = leading?.let { { Icon(it, null) } },
        textStyle = if (monospace || (secret && revealed)) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            autoCorrectEnabled = capitalize && !secret,
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
        ),
        trailingIcon = if (secret || trailing != null) {
            {
                Row {
                    trailing?.invoke()
                    if (secret) {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(if (revealed) Symbols.VisibilityOff else Symbols.Visibility, if (revealed) "Verbergen" else "Anzeigen")
                        }
                    }
                }
            }
        } else {
            null
        },
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(
                when {
                    isError -> Modifier.border(2.dp, MaterialTheme.colorScheme.error, shape)
                    focused -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    else -> Modifier
                },
            ),
    )
}

private val MONTHS = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

@Composable
fun EditScreen(state: AppState, id: String?, type: ItemType) {
    val original = id?.let { state.vault.get(it) }
    val blank = remember(id) { VaultItem.blank(type, state.folderFilter?.takeIf { it != NO_FOLDER }) }
    var draft by remember(id) { mutableStateOf(original ?: blank) }
    var nameError by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showGenerator by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val changed = draft != (original ?: blank)
    val copy = LocalCopy.current

    fun save() {
        if (draft.name.isBlank()) {
            // Websites often name the entry: take the host if the user left it empty.
            val host = draft.login?.allUris?.firstOrNull()?.let(VaultItem::hostOf)
            if (host.isNullOrEmpty()) {
                nameError = true
                return
            }
            draft = draft.copy(name = host.replaceFirstChar { it.uppercaseChar() })
        }
        // Drop empty website rows and fields without a name before saving.
        val clean = draft.copy(
            name = draft.name.trim(),
            login = draft.login?.let { it.withUris(it.allUris) },
            fields = draft.fields.filter { it.name.isNotBlank() || it.value.isNotBlank() },
        )
        val stored = state.vault.save(clean)
        if (id == null) state.replaceTop(Route.Detail(stored.id)) else state.back()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (changed) confirmDiscard = true else state.back() }) { Icon(Symbols.Close, "Schließen") }
                Text(if (id == null) "Neuer Eintrag" else "Bearbeiten", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                TextButton(onClick = ::save, enabled = changed || id == null) { Text("Speichern") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 144.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                EditHeader(draft, id == null)

                if (id == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Art")
                        ConnectedGroup(
                            ItemType.entries.map { t ->
                                GroupOption(t.short.let { if (t == ItemType.Identity) "ID" else it }, null, draft.type == t) {
                                    if (draft.type != t) draft = VaultItem.blank(t, draft.folderId).copy(name = draft.name, notes = draft.notes, favorite = draft.favorite, fields = draft.fields)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(draft.type.label)
                    BoxSection {
                        TextInput("Name", draft.name, { draft = draft.copy(name = it); nameError = false }, capitalize = true, isError = nameError, supporting = if (nameError) "Gib dem Eintrag einen Namen." else null)
                        when (draft.type) {
                            ItemType.Login -> LoginFields(draft, { draft = it }, onGenerate = { showGenerator = true }) { copy("Passwort", it, true) }
                            ItemType.Card -> CardFields(draft) { draft = it }
                            ItemType.Identity -> IdentityFields(draft) { draft = it }
                            ItemType.SshKey -> SshFields(draft) { draft = it }
                            ItemType.Note -> TextInput("Notiz", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false, minLines = 8, capitalize = true)
                        }
                    }
                }

                if (draft.type == ItemType.Login) Websites(draft) { draft = it }

                if (draft.type == ItemType.Identity) IdentityAddress(draft) { draft = it }

                if (draft.type != ItemType.Note) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Notizen")
                        BoxSection { TextInput("Notizen", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false, minLines = 3, capitalize = true) }
                    }
                }

                CustomFields(draft) { draft = it }
                Options(state, draft) { draft = it }

                if (original != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Symbols.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("In den Papierkorb")
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = ::save,
            icon = { Icon(Symbols.Check, null) },
            text = { Text(if (id == null) "Anlegen" else "Speichern") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding().padding(16.dp),
        )
    }

    if (showGenerator) {
        ModalBottomSheet(onDismissRequest = { showGenerator = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Passwort generieren", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.padding(horizontal = 4.dp))
                GeneratorPanel(state.generator, showHistory = false)
                Button(
                    onClick = {
                        val login = draft.login ?: return@Button
                        state.generator.record(state.generator.value)
                        draft = draft.copy(login = login.copy(password = state.generator.value))
                        showGenerator = false
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 0.dp),
                ) { Text("Übernehmen", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            icon = { Icon(Symbols.Edit, null) },
            title = { Text("Änderungen verwerfen?") },
            text = { Text("Deine Änderungen an diesem Eintrag gehen verloren.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; state.back() }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Weiter bearbeiten") } },
        )
    }
    if (confirmDelete && original != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Symbols.Delete, null) },
            title = { Text("Eintrag löschen?") },
            text = { Text("„${original.name}“ wird in den Papierkorb verschoben und lässt sich in der Weboberfläche 30 Tage lang wiederherstellen.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    state.vault.delete(original.id)
                    state.backStack.clear()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun EditHeader(draft: VaultItem, isNew: Boolean) {
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val brand = if (AppSettings.showLogos) Brands.forItem(draft) else null
        SpinningShape(draft.type.polygon, draft.type.containerColor, 80.dp) {
            if (brand != null) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color(brand.bg)), contentAlignment = Alignment.Center) {
                    Icon(Brands.icon(brand), null, tint = Color(brand.fg), modifier = Modifier.size(26.dp))
                }
            } else {
                Icon(draft.type.icon, null, tint = draft.type.contentColor, modifier = Modifier.size(32.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(draft.name.ifBlank { if (isNew) "Neuer Eintrag" else "Ohne Namen" }, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (isNew) "${draft.type.label} anlegen" else draft.type.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoginFields(draft: VaultItem, onChange: (VaultItem) -> Unit, onGenerate: () -> Unit, onCopy: (String) -> Unit) {
    val login = draft.login ?: return
    TextInput("Benutzername", login.username, { onChange(draft.copy(login = login.copy(username = it))) }, leading = Symbols.Person, keyboardType = KeyboardType.Email)
    TextInput("Passwort", login.password, { onChange(draft.copy(login = login.copy(password = it))) }, leading = Symbols.Key, secret = true)
    if (login.password.isNotEmpty()) WavyStrength(PasswordGenerator.estimateBits(login.password), Modifier.padding(horizontal = 4.dp))
    ConnectedGroup(
        listOf(
            GroupOption("Generieren", Symbols.Casino) { onGenerate() },
            GroupOption("Kopieren", Symbols.ContentCopy) { if (login.password.isNotEmpty()) onCopy(login.password) },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    TextInput(
        "Authenticator-Schlüssel",
        login.totpSecret.orEmpty(),
        { onChange(draft.copy(login = login.copy(totpSecret = it.trim().ifEmpty { null }))) },
        leading = Symbols.Timer,
        supporting = "Optional, für Bestätigungscodes (Schlüssel oder otpauth://)",
    )
}

@Composable
private fun Websites(draft: VaultItem, onChange: (VaultItem) -> Unit) {
    val login = draft.login ?: return
    val uris = if (login.uri.isEmpty() && login.additionalUris.isEmpty()) listOf("") else listOf(login.uri) + login.additionalUris
    fun set(list: List<String>) = onChange(draft.copy(login = login.copy(uri = list.firstOrNull().orEmpty(), additionalUris = list.drop(1))))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Websites")
        BoxSection {
            uris.forEachIndexed { index, uri ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val brand = if (AppSettings.showLogos) Brands.forUri(uri) else null
                    Box(
                        Modifier.padding(top = 6.dp).size(44.dp).clip(RoundedCornerShape(14.dp)).background(brand?.let { Color(it.bg) } ?: MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(brand?.let { Brands.icon(it) } ?: Symbols.Language, null, tint = brand?.let { Color(it.fg) } ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    }
                    TextInput(
                        if (uris.size > 1) "Website ${index + 1}" else "Website",
                        uri,
                        { value -> set(uris.toMutableList().also { it[index] = value }) },
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Uri,
                        supporting = VaultItem.hostOf(uri).takeIf { uri.isNotBlank() && it.isNotEmpty() }?.let { "Füllt auf $it aus" },
                        trailing = if (uris.size > 1 || uri.isNotEmpty()) {
                            { IconButton(onClick = { set(uris.toMutableList().also { it.removeAt(index) }) }) { Icon(Symbols.Close, "Entfernen") } }
                        } else {
                            null
                        },
                    )
                }
            }
            TextButton(onClick = { set(uris + "") }) {
                Icon(Symbols.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Website hinzufügen")
            }
        }
    }
}

@Composable
private fun CardFields(draft: VaultItem, onChange: (VaultItem) -> Unit) {
    val card = draft.card ?: return
    val brand = Brands.forItem(draft)
    // Card preview in the brand's colors.
    Column(
        Modifier.fillMaxWidth().height(188.dp).clip(RoundedCornerShape(20.dp))
            .background(brand?.let { Color(it.bg) } ?: MaterialTheme.colorScheme.tertiaryContainer).padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        val fg = brand?.let { Color(it.fg) } ?: MaterialTheme.colorScheme.onTertiaryContainer
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(Symbols.CreditCard, null, tint = fg, modifier = Modifier.size(28.dp))
            if (brand != null) Icon(Brands.icon(brand), null, tint = fg, modifier = Modifier.size(44.dp))
        }
        Text(
            card.number.ifEmpty { "•••• •••• •••• ••••" }.chunked(4).joinToString(" "),
            fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 2.sp, color = fg,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(card.cardholder.ifEmpty { "KARTENINHABER" }, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${card.expMonth.padStart(2, '0').ifEmpty { "MM" }}/${card.expYear.takeLast(2).ifEmpty { "JJ" }}", style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
    TextInput("Karteninhaber", card.cardholder, { onChange(draft.copy(card = card.copy(cardholder = it))) }, leading = Symbols.Person, capitalize = true)
    TextInput("Kartennummer", card.number, { v -> onChange(draft.copy(card = card.copy(number = v.filter { it.isDigit() }.take(19)))) }, leading = Symbols.CreditCard, keyboardType = KeyboardType.Number, monospace = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextInput("Monat", card.expMonth, { v -> onChange(draft.copy(card = card.copy(expMonth = v.filter { it.isDigit() }.take(2)))) }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
        TextInput("Jahr", card.expYear, { v -> onChange(draft.copy(card = card.copy(expYear = v.filter { it.isDigit() }.take(4)))) }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
        TextInput("CVC", card.code, { v -> onChange(draft.copy(card = card.copy(code = v.filter { it.isDigit() }.take(4)))) }, Modifier.weight(1f), secret = true, keyboardType = KeyboardType.NumberPassword)
    }
    var brandMenu by remember { mutableStateOf(false) }
    Box {
        ListRowLike("Marke", card.brand.ifEmpty { "Automatisch" }) { brandMenu = true }
        DropdownMenu(expanded = brandMenu, onDismissRequest = { brandMenu = false }) {
            (listOf("") + listOf("Visa", "Mastercard", "Amex", "Discover", "Diners Club", "JCB", "Maestro", "UnionPay", "RuPay", "Other")).forEach { b ->
                DropdownMenuItem(text = { Text(b.ifEmpty { "Keine Angabe" }) }, onClick = { onChange(draft.copy(card = card.copy(brand = b))); brandMenu = false })
            }
        }
    }
    if (card.expMonth.toIntOrNull()?.let { it !in 1..12 } == true) {
        Text("Monat 1–12 (${MONTHS.first()} bis ${MONTHS.last()})", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ListRowLike(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        IconButton(onClick = onClick) { Icon(Symbols.ArrowDropDown, "Auswählen") }
    }
}

@Composable
private fun IdentityFields(draft: VaultItem, onChange: (VaultItem) -> Unit) {
    val id = draft.identity ?: return
    fun set(block: IdentityData.() -> IdentityData) = onChange(draft.copy(identity = id.block()))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextInput("Vorname", id.firstName, { v -> set { copy(firstName = v) } }, Modifier.weight(1f), leading = Symbols.Person, capitalize = true)
        TextInput("Nachname", id.lastName, { v -> set { copy(lastName = v) } }, Modifier.weight(1f), capitalize = true)
    }
    TextInput("E-Mail", id.email, { v -> set { copy(email = v) } }, leading = Symbols.Mail, keyboardType = KeyboardType.Email)
    TextInput("Telefon", id.phone, { v -> set { copy(phone = v) } }, leading = Symbols.Call, keyboardType = KeyboardType.Phone)
    TextInput("Firma", id.company, { v -> set { copy(company = v) } }, leading = Symbols.Home, capitalize = true)
    TextInput("Benutzername", id.username, { v -> set { copy(username = v) } }, leading = Symbols.Person)
}

@Composable
private fun IdentityAddress(draft: VaultItem, onChange: (VaultItem) -> Unit) {
    val id = draft.identity ?: return
    fun set(block: IdentityData.() -> IdentityData) = onChange(draft.copy(identity = id.block()))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Adresse")
        BoxSection {
            TextInput("Straße und Hausnummer", id.address1, { v -> set { copy(address1 = v) } }, leading = Symbols.Home, capitalize = true)
            TextInput("Adresszusatz", id.address2, { v -> set { copy(address2 = v) } }, capitalize = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextInput("PLZ", id.postalCode, { v -> set { copy(postalCode = v) } }, Modifier.weight(0.4f), keyboardType = KeyboardType.Number)
                TextInput("Ort", id.city, { v -> set { copy(city = v) } }, Modifier.weight(0.6f), capitalize = true)
            }
            TextInput("Land", id.country, { v -> set { copy(country = v) } }, capitalize = true)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Ausweise")
        BoxSection {
            TextInput("Reisepassnummer", id.passportNumber, { v -> set { copy(passportNumber = v) } }, leading = Symbols.IdCard, secret = true)
            TextInput("Führerscheinnummer", id.licenseNumber, { v -> set { copy(licenseNumber = v) } }, leading = Symbols.IdCard, secret = true)
            TextInput("Sozialversicherungsnummer", id.ssn, { v -> set { copy(ssn = v) } }, leading = Symbols.IdCard, secret = true)
        }
    }
}

@Composable
private fun SshFields(draft: VaultItem, onChange: (VaultItem) -> Unit) {
    val key = draft.sshKey ?: return
    TextInput("Öffentlicher Schlüssel", key.publicKey, { onChange(draft.copy(sshKey = key.copy(publicKey = it.trim()))) }, singleLine = false, minLines = 3, monospace = true)
    TextInput("Privater Schlüssel", key.privateKey, { onChange(draft.copy(sshKey = key.copy(privateKey = it))) }, singleLine = false, minLines = 3, monospace = true, supporting = "OpenSSH-Format, z. B. aus ~/.ssh/id_ed25519")
    TextInput("Fingerabdruck", key.fingerprint, { onChange(draft.copy(sshKey = key.copy(fingerprint = it.trim()))) }, monospace = true, supporting = "SHA256:… (ssh-keygen -lf)")
}

@Composable
private fun CustomFields(draft: VaultItem, onChange: (VaultItem) -> Unit) {
    var addMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Eigene Felder")
        BoxSection(Modifier.animateContentSize()) {
            if (draft.fields.isEmpty()) {
                Text("Zusätzliche Angaben wie Kundennummer oder PIN.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            draft.fields.forEachIndexed { index, field ->
                fun update(f: CustomField) = onChange(draft.copy(fields = draft.fields.toMutableList().also { it[index] = f }))
                val remove: @Composable () -> Unit = {
                    IconButton(onClick = { onChange(draft.copy(fields = draft.fields.toMutableList().also { it.removeAt(index) })) }) { Icon(Symbols.Close, "Feld entfernen") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("Feldname", field.name, { update(field.copy(name = it)) }, capitalize = true, trailing = remove)
                    when (field.type) {
                        CustomFieldType.Boolean -> Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Wert", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = field.value.toBooleanStrictOrNull() == true, onCheckedChange = { update(field.copy(value = it.toString())) })
                        }
                        CustomFieldType.Linked -> Text("Verknüpfte Felder lassen sich nur in der Weboberfläche ändern.", style = MaterialTheme.typography.bodySmall)
                        else -> TextInput("Wert", field.value, { update(field.copy(value = it)) }, leading = if (field.type == CustomFieldType.Hidden) Symbols.Password else Symbols.Notes, secret = field.type == CustomFieldType.Hidden)
                    }
                }
            }
            Box {
                TextButton(onClick = { addMenu = true }) {
                    Icon(Symbols.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Feld hinzufügen")
                    Icon(Symbols.ArrowDropDown, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    listOf(Triple(CustomFieldType.Text, "Text", Symbols.Notes), Triple(CustomFieldType.Hidden, "Verborgen (z. B. PIN)", Symbols.Password), Triple(CustomFieldType.Boolean, "Ja/Nein", Symbols.Check)).forEach { (t, l, i) ->
                        DropdownMenuItem(text = { Text(l) }, leadingIcon = { Icon(i, null) }, onClick = {
                            addMenu = false
                            onChange(draft.copy(fields = draft.fields + CustomField(type = t, value = if (t == CustomFieldType.Boolean) "false" else "")))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Options(state: AppState, draft: VaultItem, onChange: (VaultItem) -> Unit) {
    var folderMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Optionen")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
                ListRow(
                    "Ordner",
                    segmentShape(0, 2),
                    supporting = draft.folderId?.let { id -> state.vault.folders.firstOrNull { it.id == id }?.name } ?: "Kein Ordner",
                    icon = Symbols.Folder,
                    onClick = { folderMenu = true },
                ) { Icon(Symbols.ArrowDropDown, null) }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    DropdownMenuItem(text = { Text("Kein Ordner") }, trailingIcon = { if (draft.folderId == null) Icon(Symbols.Check, null) }, onClick = { onChange(draft.copy(folderId = null)); folderMenu = false })
                    state.vault.folders.forEach { f ->
                        DropdownMenuItem(text = { Text(f.name) }, leadingIcon = { Icon(Symbols.Folder, null) }, trailingIcon = { if (draft.folderId == f.id) Icon(Symbols.Check, null) }, onClick = { onChange(draft.copy(folderId = f.id)); folderMenu = false })
                    }
                    DropdownMenuItem(text = { Text("Neuer Ordner…") }, leadingIcon = { Icon(Symbols.Add, null) }, onClick = { folderMenu = false; newFolder = "" })
                }
            }
            ListRow("Favorit", segmentShape(1, 2), supporting = "Oben im Tresor anzeigen", icon = Symbols.Star, onClick = { onChange(draft.copy(favorite = !draft.favorite)) }) {
                Switch(checked = draft.favorite, onCheckedChange = { onChange(draft.copy(favorite = it)) })
            }
        }
    }
    newFolder?.let { text ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            icon = { Icon(Symbols.Folder, null) },
            title = { Text("Neuer Ordner") },
            text = { TextInput("Name", text, { newFolder = it }, capitalize = true) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    onChange(draft.copy(folderId = state.vault.createFolder(text).id))
                    newFolder = null
                }) { Text("Anlegen") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Abbrechen") } },
        )
    }
}
