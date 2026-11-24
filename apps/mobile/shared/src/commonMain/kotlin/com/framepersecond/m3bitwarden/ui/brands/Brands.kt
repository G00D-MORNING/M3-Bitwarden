package com.framepersecond.m3bitwarden.ui.brands

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.data.VaultItem

/** Local logo index: website first, then card brand, then name (same rules as the prototype). */
object Brands {
    private val cache = HashMap<String, ImageVector>()

    fun forItem(item: VaultItem): Brand? {
        val hosts = item.login?.allUris.orEmpty().map { VaultItem.hostOf(it).lowercase() }.filter { it.isNotEmpty() }
        for (host in hosts) {
            BRANDS.firstOrNull { b ->
                b.domains.any { host == it || host.endsWith(".$it") } || host.split('.').contains(b.slug)
            }?.let { return it }
        }
        val words = ((item.card?.brand ?: "") + " " + item.name).lowercase().replace(Regex("[^a-z0-9äöü ]"), " ")
        val tokens = words.split(' ').filter { it.isNotEmpty() }
        return BRANDS.firstOrNull { b -> words.contains(b.title.lowercase()) || tokens.contains(b.slug) }
    }

    fun forUri(uri: String): Brand? {
        val host = VaultItem.hostOf(uri).lowercase()
        if (host.isEmpty()) return null
        return BRANDS.firstOrNull { b -> b.domains.any { host == it || host.endsWith(".$it") } || host.split('.').contains(b.slug) }
    }

    fun icon(brand: Brand): ImageVector = cache.getOrPut(brand.slug) {
        ImageVector.Builder(brand.slug, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(brand.path), fill = SolidColor(Color.Black))
            .build()
    }

    val all: List<Brand> get() = BRANDS
}
