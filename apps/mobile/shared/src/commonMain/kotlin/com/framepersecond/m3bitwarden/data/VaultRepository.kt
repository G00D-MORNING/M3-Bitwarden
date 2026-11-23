package com.framepersecond.m3bitwarden.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * In-memory list of decrypted items. Holds demo data until a [VaultBackend] is connected; then
 * it mirrors the server (filled after unlock, emptied on lock) and forwards every change to the
 * backend, which encrypts and uploads it. Nothing here is ever written to disk.
 */
@Stable
class VaultRepository(initial: List<VaultItem> = SampleVault.items) {
    private val _items: SnapshotStateList<VaultItem> = mutableStateListOf<VaultItem>().apply { addAll(initial) }
    val items: List<VaultItem> get() = _items

    val folders: List<String>
        get() {
            val demo = if (VaultSession.backend == null) SampleVault.folders else emptyList()
            return (demo + _items.mapNotNull { it.folder }).distinct().sorted()
        }

    private var nextId = (initial.maxOfOrNull { it.id.toIntOrNull() ?: 0 } ?: 0) + 1

    fun get(id: String): VaultItem? = _items.firstOrNull { it.id == id }

    /** Replaces everything, e.g. after a sync. */
    fun replaceAll(items: List<VaultItem>) {
        _items.clear()
        _items.addAll(items)
    }

    /** Inserts a new item (blank id) or replaces the existing one. Returns the stored item. */
    fun save(item: VaultItem): VaultItem {
        val isNew = item.id.isBlank()
        val stored = if (isNew) item.copy(id = "$LOCAL_PREFIX${nextId++}") else item
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

    companion object {
        /** Prefix of ids not yet known to the server. */
        const val LOCAL_PREFIX = "local-"
    }
}
