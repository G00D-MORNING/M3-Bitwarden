package com.framepersecond.m3bitwarden.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.framepersecond.m3bitwarden.HideNativeChrome
import com.framepersecond.m3bitwarden.NativeChrome
import com.framepersecond.m3bitwarden.ui.hero
import com.framepersecond.m3bitwarden.ui.riseIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.AppSettings
import com.framepersecond.m3bitwarden.AppState
import com.framepersecond.m3bitwarden.Route
import com.framepersecond.m3bitwarden.VaultFilter
import com.framepersecond.m3bitwarden.VaultSort
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.security.SecurityCheck
import com.framepersecond.m3bitwarden.security.SecurityReport
import com.framepersecond.m3bitwarden.ui.LocalCopy
import com.framepersecond.m3bitwarden.ui.brands.Brands
import com.framepersecond.m3bitwarden.ui.components.ConnectedGroup
import com.framepersecond.m3bitwarden.ui.components.GroupOption
import com.framepersecond.m3bitwarden.ui.components.ItemTile
import com.framepersecond.m3bitwarden.ui.components.ListRow
import com.framepersecond.m3bitwarden.ui.components.SectionTitle
import com.framepersecond.m3bitwarden.ui.components.SpinningShape
import com.framepersecond.m3bitwarden.ui.components.WavyRing
import com.framepersecond.m3bitwarden.ui.components.contentColor
import com.framepersecond.m3bitwarden.ui.components.containerColor
import com.framepersecond.m3bitwarden.ui.components.icon
import com.framepersecond.m3bitwarden.ui.components.label
import com.framepersecond.m3bitwarden.ui.components.segmentShape
import com.framepersecond.m3bitwarden.ui.icons.Symbols

const val NO_FOLDER = "__none__"

/** Items matching search, type filter and folder, in the chosen order. */
fun AppState.visibleItems(): List<VaultItem> {
    val q = query.trim()
    val list = vault.items.asSequence()
        .filter { item ->
            when (filter) {
                VaultFilter.All -> true
                VaultFilter.Favorites -> item.favorite
                else -> item.type == filter.type
            }
        }
        .filter { folderFilter == null || it.folderId == folderFilter || (folderFilter == NO_FOLDER && it.folderId == null) }
        .filter { item ->
            q.isEmpty() ||
                item.name.contains(q, true) ||
                item.login?.username?.contains(q, true) == true ||
                item.login?.allUris.orEmpty().any { it.contains(q, true) } ||
                item.notes.contains(q, true) ||
                item.folder?.contains(q, true) == true ||
                item.card?.brand?.contains(q, true) == true
        }
    return when (AppSettings.sort) {
        VaultSort.Name -> list.sortedBy { it.name.lowercase() }
        VaultSort.Recent -> list.sortedByDescending { it.revisionEpoch }
        VaultSort.Type -> list.sortedWith(compareBy<VaultItem> { it.type.ordinal }.thenBy { it.name.lowercase() })
    }.toList()
}

/** List subtitle: the website with its subdomain, so services on one domain stay apart. */
fun listSubtitle(item: VaultItem, duplicateHost: Boolean): String = when (item.type) {
    ItemType.Login -> {
        val host = item.login?.allUris?.firstOrNull()?.let(VaultItem::hostOf).orEmpty()
        val user = item.login?.username.orEmpty()
        when {
            host.isEmpty() -> user.ifEmpty { "Login" }
            duplicateHost && user.isNotEmpty() -> "$host · $user"
            else -> host
        }
    }
    ItemType.Card -> item.card?.brand?.takeIf { it.isNotEmpty() }?.let { b -> item.card.number.takeLast(4).takeIf { it.isNotEmpty() }?.let { "$b, *$it" } ?: b } ?: "Karte"
    ItemType.Identity -> item.identity?.fullName?.ifEmpty { null } ?: "Identität"
    ItemType.Note -> "Sichere Notiz"
    ItemType.SshKey -> "SSH-Schlüssel"
}

private val VaultFilter.icon
    get() = when (this) {
        VaultFilter.All -> Symbols.Apps
        VaultFilter.Favorites -> Symbols.Star
        VaultFilter.Logins -> Symbols.Key
        VaultFilter.Cards -> Symbols.CreditCard
        VaultFilter.Identities -> Symbols.IdCard
        VaultFilter.Notes -> Symbols.StickyNote
        VaultFilter.SshKeys -> Symbols.Terminal
    }

@Composable
fun VaultScreen(state: AppState, contentPadding: PaddingValues) {
    val items = state.visibleItems()
    val searching = state.query.isNotBlank()
    val showFavorites = AppSettings.showFavoritesRow && !searching && state.filter == VaultFilter.All && state.folderFilter == null
    val favorites = if (showFavorites) items.filter { it.favorite } else emptyList()
    val report = remember(state.vault.items.toList()) { SecurityCheck.run(state.vault.items) }
    val hostCounts = remember(items) {
        items.mapNotNull { it.login?.allUris?.firstOrNull()?.let(VaultItem::hostOf) }.groupingBy { it }.eachCount()
    }
    var fabOpen by remember { mutableStateOf(false) }
    var securityOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var demoRefreshing by remember { mutableStateOf(false) }
    val refreshing = VaultSession.syncing || demoRefreshing
    val pullState = rememberPullToRefreshState()

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                if (state.live) {
                    VaultSession.backend?.refresh()
                } else {
                    scope.launch { demoRefreshing = true; delay(DEMO_REFRESH_MS); demoRefreshing = false }
                }
            },
            state = pullState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding()),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = state.vaultListState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 200.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                block("header") { Header(state) }
                block("search") { SearchBar(state) }
                block("filters") { Filters(state) }
                if (state.live && VaultSession.syncStatus == "Nicht aktualisiert") block("offline") { OfflineBanner() }
                if (report.findings.isNotEmpty() || state.vault.items.any { it.type == ItemType.Login }) {
                    block("security") { SecurityCard(report) { securityOpen = true } }
                }
                if (favorites.isNotEmpty()) block("favs") { Favorites(favorites, state) }
                block("listHead", gap = 8.dp) { ListHeader(state, items.size, searching) }
                if (items.isEmpty()) {
                    block("empty") { Empty(state) }
                } else {
                    itemsIndexed(items, key = { _, item -> item.id }) { i, item ->
                        val host = item.login?.allUris?.firstOrNull()?.let(VaultItem::hostOf)
                        VaultRow(
                            item = item,
                            subtitle = listSubtitle(item, (hostCounts[host] ?: 0) > 1),
                            index = i,
                            count = items.size,
                            state = state,
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    placementSpec = spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold),
                                    fadeOutSpec = tween(120),
                                )
                                .padding(bottom = if (i < items.lastIndex) 2.dp else 0.dp)
                                .riseIn(item.id, i, state.seenRows),
                        )
                    }
                }
            }
        }

        if (!NativeChrome.enabled) {
            // Follows the navigation bar down when it tucks away.
            val lift by animateDpAsState(if (state.chromeCompact) 16.dp else 88.dp, spring(dampingRatio = 0.7f, stiffness = 400f))
            FloatingActionButtonMenu(
                expanded = fabOpen,
                button = {
                    ToggleFloatingActionButton(checked = fabOpen, onCheckedChange = { fabOpen = it }) {
                        Icon(if (checkedProgress > 0.5f) Symbols.Close else Symbols.Add, "Neuer Eintrag", Modifier.animateIcon({ checkedProgress }))
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = contentPadding.calculateBottomPadding() + lift),
            ) {
                ItemType.entries.forEach { type ->
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabOpen = false
                            state.open(Route.Edit(null, type))
                        },
                        icon = { Icon(type.icon, null) },
                        text = { Text(type.label) },
                    )
                }
            }
        }
    }

    if (securityOpen) SecuritySheet(report, state) { securityOpen = false }
}

private const val DEMO_REFRESH_MS = 900L

/** A list block with the usual gap below it; it slides when blocks above appear or disappear. */
private fun LazyListScope.block(key: String, gap: Dp = 16.dp, content: @Composable () -> Unit) {
    item(key = key) {
        Box(Modifier.animateItem(fadeInSpec = tween(200), placementSpec = spring(dampingRatio = 0.85f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold), fadeOutSpec = tween(120)).padding(bottom = gap)) {
            content()
        }
    }
}

@Composable
private fun Header(state: AppState) {
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Tresor", style = MaterialTheme.typography.displaySmallEmphasized)
        val folders = state.vault.folders.size
        Text(
            "${state.vault.items.size} Einträge" + if (folders > 0) " · $folders Ordner" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusChip(state)
    }
}

/** Connection status, like the prototype's tunnel chip: tap to sync. */
@Composable
private fun StatusChip(state: AppState) {
    val syncing = VaultSession.syncing
    val failed = VaultSession.syncStatus == "Nicht aktualisiert" || VaultSession.syncStatus == "Offline-Daten"
    val (bg, fg) = when {
        !state.live -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    val label = when {
        !state.live -> "Demo-Daten"
        syncing -> "Wird synchronisiert"
        failed -> "Offline · Tippen zum Verbinden"
        else -> VaultSession.syncStatus.ifEmpty { "Verbunden" }
    }
    Row(
        Modifier
            .padding(top = 10.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = state.live && !syncing) { VaultSession.backend?.refresh() }
            .padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (syncing) {
            LoadingIndicator(Modifier.size(20.dp), color = fg)
        } else {
            Icon(if (failed) Symbols.CloudOff else Symbols.CloudDone, null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLargeEmphasized, color = fg)
    }
}

@Composable
private fun SearchBar(state: AppState) {
    var accountMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Symbols.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f)) {
            if (state.query.isEmpty()) Text("Tresor durchsuchen", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = state.query,
                onValueChange = { state.query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.query.isNotEmpty()) {
            IconButton(onClick = { state.query = "" }) { Icon(Symbols.Close, "Suche löschen") }
        }
        Box {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { accountMenu = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(VaultSession.accountEmail.firstOrNull()?.uppercase() ?: "A", style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.onPrimary)
            }
            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                Text(VaultSession.accountEmail.ifEmpty { "Demo" }, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenuItem(text = { Text("Tresor sperren") }, leadingIcon = { Icon(Symbols.Lock, null) }, onClick = { accountMenu = false; state.lock() })
                if (state.live) {
                    DropdownMenuItem(text = { Text("Jetzt synchronisieren") }, leadingIcon = { Icon(Symbols.Sync, null) }, onClick = { accountMenu = false; VaultSession.backend?.refresh() })
                }
            }
        }
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun Filters(state: AppState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        val options = VaultFilter.entries.filter { f ->
            f == VaultFilter.All || f == state.filter || when (f) {
                VaultFilter.Favorites -> state.vault.items.any { it.favorite }
                else -> state.vault.items.any { it.type == f.type }
            }
        }.map { f ->
            GroupOption(f.label, f.icon, state.filter == f) {
                state.filter = if (state.filter == f && f != VaultFilter.All) VaultFilter.All else f
            }
        }
        val folderName = state.folderFilter?.let { id -> state.vault.folders.firstOrNull { it.id == id }?.name ?: "Ohne Ordner" }
        var folderMenu by remember { mutableStateOf(false) }
        ConnectedGroup(
            options + if (state.vault.folders.isNotEmpty()) listOf(GroupOption(folderName ?: "Ordner", Symbols.Folder, state.folderFilter != null) { folderMenu = true }) else emptyList(),
            fill = false,
        )
        Box {
            DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                DropdownMenuItem(text = { Text("Alle Ordner") }, onClick = { state.folderFilter = null; folderMenu = false })
                state.vault.folders.forEach { folder ->
                    DropdownMenuItem(text = { Text(folder.name) }, leadingIcon = { Icon(Symbols.Folder, null) }, onClick = { state.folderFilter = folder.id; folderMenu = false })
                }
                DropdownMenuItem(text = { Text("Ohne Ordner") }, onClick = { state.folderFilter = NO_FOLDER; folderMenu = false })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Ordner verwalten") }, leadingIcon = { Icon(Symbols.Edit, null) }, onClick = { folderMenu = false; state.open(Route.Folders) })
            }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Symbols.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keine Verbindung zum Server", style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.onErrorContainer)
            Text("Du siehst die zuletzt synchronisierte Kopie. Änderungen werden hochgeladen, sobald die Verbindung steht.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            androidx.compose.material3.Button(onClick = { VaultSession.backend?.refresh() }) {
                Icon(Symbols.Sync, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Neu verbinden")
            }
        }
    }
}

@Composable
private fun SecurityCard(report: SecurityReport, onClick: () -> Unit) {
    val title = when {
        report.score >= 90 -> "Sicherheitswert sehr gut"
        report.score >= 70 -> "Sicherheitswert gut"
        report.score >= 40 -> "Sicherheitswert mittel"
        else -> "Sicherheitswert niedrig"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WavyRing(report.score / 100f, "${report.score}", 56.dp, color = if (report.score >= 70) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
            Text(
                if (report.count == 0) "Keine schwachen oder doppelten Passwörter" else "${report.count} Hinweise · lokal geprüft",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Symbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SecuritySheet(report: SecurityReport, state: AppState, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        HideNativeChrome()
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WavyRing(report.score / 100f, "${report.score}", 64.dp)
                Column {
                    Text("Sicherheitscheck", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text("Wird lokal auf diesem Gerät geprüft", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (report.findings.isEmpty()) {
                Text("Alles in Ordnung: keine schwachen oder mehrfach verwendeten Passwörter.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(8.dp))
            }
            val rows = report.findings.flatMap { f -> f.issues.map { f to it } }
            LazyColumn(Modifier.height((rows.size * 74).coerceAtMost(420).dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(rows) { i, (finding, issue) ->
                    ListRow(
                        headline = finding.item.name,
                        shape = segmentShape(i, rows.size),
                        supporting = issue.text,
                        icon = when (issue.kind) {
                            com.framepersecond.m3bitwarden.security.IssueKind.Reused -> Symbols.ContentCopy
                            com.framepersecond.m3bitwarden.security.IssueKind.Weak -> Symbols.Warning
                            com.framepersecond.m3bitwarden.security.IssueKind.TwoFactor -> Symbols.Passkey
                        },
                        iconBg = if (issue.serious) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        iconTint = if (issue.serious) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = { onClose(); state.openItem(finding.item.id, "sheet") },
                    ) { Text(issue.kind.short, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun Favorites(favorites: List<VaultItem>, state: AppState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Favoriten")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
            items(favorites, key = { it.id }) { item ->
                val brand = if (AppSettings.showLogos) Brands.forItem(item) else null
                val bg = brand?.let { Color(it.bg) } ?: item.type.containerColor
                val fg = brand?.let { Color(it.fg) } ?: item.type.contentColor
                Column(
                    Modifier.hero("fav-tile-${item.id}").width(132.dp).height(152.dp).clip(RoundedCornerShape(32.dp)).background(bg).clickable { state.openItem(item.id, "fav") }.padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(brand?.let { Brands.icon(it) } ?: item.type.icon, null, tint = fg, modifier = Modifier.size(36.dp))
                    Column {
                        Text(item.name, style = MaterialTheme.typography.titleMediumEmphasized, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.hero("fav-name-${item.id}"))
                        Text(listSubtitle(item, false), style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListHeader(state: AppState, count: Int, searching: Boolean) {
    var sortMenu by remember { mutableStateOf(false) }
    val title = when {
        searching -> "$count Treffer"
        state.folderFilter != null -> state.vault.folders.firstOrNull { it.id == state.folderFilter }?.name ?: "Ohne Ordner"
        state.filter == VaultFilter.All -> "Alle Einträge"
        else -> state.filter.label
    }
    SectionTitle(title) {
        Box {
            TextButton(onClick = { sortMenu = true }) {
                Icon(Symbols.Sort, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(AppSettings.sort.label)
                Icon(Symbols.ArrowDropDown, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                Text("Sortieren nach", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                VaultSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        trailingIcon = { if (AppSettings.sort == s) Icon(Symbols.Check, null) },
                        onClick = { AppSettings.updateSort(s); sortMenu = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Favoriten oben") },
                    trailingIcon = { if (AppSettings.showFavoritesRow) Icon(Symbols.Check, null) },
                    onClick = { AppSettings.updateShowFavoritesRow(!AppSettings.showFavoritesRow); sortMenu = false },
                )
            }
        }
    }
}

@Composable
private fun VaultRow(item: VaultItem, subtitle: String, index: Int, count: Int, state: AppState, modifier: Modifier = Modifier) {
    val copy = LocalCopy.current
    // M3 Expressive press: the row rounds off and sinks a little while held.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val cornerSpring = spring<Dp>(dampingRatio = 0.6f, stiffness = 500f)
    val top by animateDpAsState(if (pressed) 28.dp else if (index == 0) 24.dp else 4.dp, cornerSpring)
    val bottom by animateDpAsState(if (pressed) 28.dp else if (index == count - 1) 24.dp else 4.dp, cornerSpring)
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f))
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .fillMaxWidth()
            .clip(RoundedCornerShape(top, top, bottom, bottom))
            .background(MaterialTheme.colorScheme.surface)
            .defaultMinSize(minHeight = 72.dp)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { state.openItem(item.id, "row") }
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemTile(item, modifier = Modifier.hero("row-tile-${item.id}"))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.hero("row-name-${item.id}"))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(item.favorite, enter = scaleIn(spring(dampingRatio = 0.4f, stiffness = 600f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(Symbols.StarFill, "Favorit", Modifier.padding(horizontal = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
        }
        val secret = when (item.type) {
            ItemType.Login -> item.login?.password
            ItemType.Card -> item.card?.number
            else -> null
        }?.takeIf { it.isNotEmpty() }
        if (secret != null) {
            IconButton(onClick = { copy(if (item.type == ItemType.Card) "Kartennummer" else "Passwort", secret, true) }) {
                Icon(if (item.type == ItemType.Card) Symbols.CreditCard else Symbols.Key, if (item.type == ItemType.Card) "Kartennummer kopieren" else "Passwort von ${item.name} kopieren")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun Empty(state: AppState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SpinningShape(MaterialShapes.Flower, MaterialTheme.colorScheme.secondaryContainer, 96.dp)
        val empty = state.vault.items.isEmpty()
        Text(
            when {
                empty -> "Dein Tresor ist leer"
                state.query.isNotBlank() -> "Nichts gefunden für „${state.query.trim()}“"
                else -> "Keine Einträge in „${state.filter.label}“"
            },
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Text(
            if (empty) "Lege mit + deinen ersten Eintrag an oder speichere Zugangsdaten beim Anmelden." else "Prüfe die Schreibweise oder zeig wieder alle Einträge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!empty) {
            FilledTonalButton(onClick = { state.query = ""; state.filter = VaultFilter.All; state.folderFilter = null }) {
                Icon(Symbols.FilterListOff, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Filter zurücksetzen")
            }
        }
    }
}
