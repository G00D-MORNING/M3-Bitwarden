package com.framepersecond.m3bitwarden.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * In-memory list of decrypted items. Holds demo data until a [VaultBackend] is connected; then
 * it mirrors the server (filled after unlock, emptied on lock) and forwards every change to the
 * backend, which encrypts and uploads it. Nothing here is ever written to disk.
 */
@Stable
class VaultRepository(initial: List<VaultItem> = SampleVault.items) {
    private val _items: SnapshotStateList<VaultItem> = mutableStateListOf<VaultItem>().apply {
        addAll(initial.map { if (it.folderId == null && it.folder != null) it.copy(folderId = it.folder) else it })
    }
    val items: List<VaultItem> get() = _items

    /** Folders with decrypted names, sorted by name. */
    var folders: List<Folder> by mutableStateOf(SampleVault.folders.map { Folder(it, it) })
        private set

    private var nextId = (initial.maxOfOrNull { it.id.toIntOrNull() ?: 0 } ?: 0) + 1

    /** Looks up by id; ids of items created offline resolve to their server id after upload. */
    fun get(id: String): VaultItem? =
        _items.firstOrNull { it.id == id } ?: VaultSession.idAliases[id]?.let { real -> _items.firstOrNull { it.id == real } }

    /** Replaces everything, e.g. after a sync. */
    fun replaceAll(items: List<VaultItem>, folders: List<Folder> = emptyList()) {
        _items.clear()
        _items.addAll(items)
        this.folders = folders.sortedBy { it.name.lowercase() }
    }

    /** Inserts a new item (blank id) or replaces the existing one. Returns the stored item. */
    fun save(item: VaultItem): VaultItem {
        val isNew = item.id.isBlank()
        val folderName = item.folderId?.let { id -> folders.firstOrNull { it.id == id }?.name }
        val stored = (if (isNew) item.copy(id = "$LOCAL_PREFIX${nextId++}") else item).copy(folder = folderName)
        val index = _items.indexOfFirst { it.id == stored.id }
        if (index >= 0) _items[index] = stored else _items.add(stored)
        VaultSession.backend?.itemSaved(stored, isNew)
        return stored
    }

    fun delete(id: String) {
        _items.removeAll { it.id == id }
        VaultSession.backend?.itemDeleted(id)
    }

    fun toggleFavorite(id: String) {
        val index = _items.indexOfFirst { it.id == id }
        if (index >= 0) {
            _items[index] = _items[index].copy(favorite = !_items[index].favorite)
            VaultSession.backend?.itemSaved(_items[index], isNew = false)
        }
    }

    /** Creates a folder; with a backend it is encrypted and uploaded (the id arrives on sync). */
    fun createFolder(name: String): Folder {
        val clean = name.trim()
        folders.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        val backend = VaultSession.backend
        val folder = Folder(if (backend == null) clean else "$LOCAL_PREFIX${nextId++}", clean)
        folders = (folders + folder).sortedBy { it.name.lowercase() }
        backend?.folderCreated(folder)
        return folder
    }

    companion object {
        /** Prefix of ids not yet known to the server. */
        const val LOCAL_PREFIX = "local-"
    }
}
