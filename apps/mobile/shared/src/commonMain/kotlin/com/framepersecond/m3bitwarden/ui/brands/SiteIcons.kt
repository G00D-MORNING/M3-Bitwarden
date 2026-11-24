package com.framepersecond.m3bitwarden.ui.brands

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.framepersecond.m3bitwarden.AppSettings
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.platform.decodeImage
import com.framepersecond.m3bitwarden.ui.components.containerColor
import com.framepersecond.m3bitwarden.ui.components.contentColor

/**
 * Website icons for every item, loaded through the icon service of the user's own Vaultwarden
 * (the phone only talks to that server) and kept in memory only; cleared when the vault locks.
 */
object SiteIcons {
    /** host → icon; null marks a host without one (no second request). */
    private val icons = mutableStateMapOf<String, ImageBitmap?>()
    private val pending = mutableSetOf<String>()
    private val queue = ArrayDeque<String>()
    private const val PARALLEL = 6

    fun clear() {
        icons.clear()
        pending.clear()
        queue.clear()
    }

    /** First web host of the item (app ids have no icon there). */
    fun hostFor(item: VaultItem): String? = item.login?.allUris.orEmpty().firstNotNullOfOrNull { hostOf(it) }

    fun hostOf(uri: String): String? {
        val u = uri.trim()
        if (u.startsWith("androidapp://") || u.startsWith("iosapp://")) return null
        val host = VaultItem.hostOf(u).lowercase().substringBefore(':')
        return host.takeIf { it.contains('.') && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
    }

    @Composable
    fun icon(host: String?): ImageBitmap? {
        if (host == null || !AppSettings.showLogos) return null
        LaunchedEffect(host) { request(host) }
        return icons[host]
    }

    private fun request(host: String) {
        if (host in icons || host in pending || host in queue) return
        queue.addLast(host)
        pump()
    }

    private fun pump() {
        val backend = VaultSession.backend ?: return
        while (pending.size < PARALLEL && queue.isNotEmpty()) {
            val host = queue.removeFirst()
            pending += host
            backend.loadIcon(host) { bytes ->
                if (host in pending) {
                    pending -= host
                    icons[host] = bytes?.takeIf { it.isNotEmpty() }?.let(::decodeImage)
                }
                pump()
            }
        }
    }
}

/**
 * The item's mark, the same shape for every item: the website's icon, else a bundled brand
 * logo, else the first letter, each as a rounded square of [size].
 */
@Composable
fun SiteMark(item: VaultItem, size: Dp) {
    val corner = RoundedCornerShape(size * 0.24f)
    val icon = SiteIcons.icon(SiteIcons.hostFor(item))
    val brand = if (AppSettings.showLogos) Brands.forItem(item) else null
    when {
        icon != null -> Box(Modifier.size(size).clip(corner).background(Color.White), contentAlignment = Alignment.Center) {
            Image(icon, null, Modifier.size(size * 0.82f).clip(RoundedCornerShape(size * 0.16f)), contentScale = ContentScale.Fit)
        }
        brand != null -> Box(Modifier.size(size).clip(corner).background(Color(brand.bg)), contentAlignment = Alignment.Center) {
            Icon(Brands.icon(brand), null, tint = Color(brand.fg), modifier = Modifier.size(size * 0.56f))
        }
        else -> Box(Modifier.size(size).clip(corner).background(item.type.containerColor), contentAlignment = Alignment.Center) {
            Text(
                item.name.trim().firstOrNull()?.uppercase() ?: "?",
                color = item.type.contentColor,
                style = MaterialTheme.typography.titleMediumEmphasized.copy(fontSize = (size.value * 0.46f).sp),
            )
        }
    }
}
