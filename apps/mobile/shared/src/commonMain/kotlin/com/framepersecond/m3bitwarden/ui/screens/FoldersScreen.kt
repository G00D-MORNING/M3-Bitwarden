package com.framepersecond.m3bitwarden.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.AppState
import com.framepersecond.m3bitwarden.MainTab
import com.framepersecond.m3bitwarden.ui.components.ListRow
import com.framepersecond.m3bitwarden.ui.components.segmentShape
import com.framepersecond.m3bitwarden.ui.icons.Symbols

@Composable
fun FoldersScreen(state: AppState) {
    var newFolder by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.back() }) { Icon(Symbols.ArrowBack, "Zurück") }
                Text("Ordner", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (state.vault.folders.isEmpty()) {
                    Text("Noch keine Ordner. Ordner helfen, große Tresore zu sortieren.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
                val folders = state.vault.folders
                folders.forEachIndexed { i, folder ->
                    val count = state.vault.items.count { it.folderId == folder.id }
                    ListRow(folder.name, segmentShape(i, folders.size), supporting = if (count == 1) "1 Eintrag" else "$count Einträge", icon = Symbols.Folder, onClick = {
                        state.folderFilter = folder.id
                        state.selectedTab = MainTab.Vault
                        state.backStack.clear()
                    }) { Icon(Symbols.ChevronRight, null) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { newFolder = "" },
            icon = { Icon(Symbols.Add, null) },
            text = { Text("Neuer Ordner") },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }
    newFolder?.let { text ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            icon = { Icon(Symbols.Folder, null) },
            title = { Text("Neuer Ordner") },
            text = { TextInput("Name", text, { newFolder = it }, capitalize = true) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    state.vault.createFolder(text)
                    newFolder = null
                }) { Text("Anlegen") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Abbrechen") } },
        )
    }
}
