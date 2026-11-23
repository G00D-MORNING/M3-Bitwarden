package com.framepersecond.m3bitwarden.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.ui.icons.Symbols

/*
 * Every item type gets its own Material shape and tonal color, so the type is readable at a glance
 * in long lists without an extra label: logins are cookies, cards are pills, identities clovers,
 * notes soft squares and SSH keys suns.
 */

val ItemType.label: String
    get() = when (this) {
        ItemType.Login -> "Login"
        ItemType.Card -> "Karte"
        ItemType.Identity -> "Identität"
        ItemType.Note -> "Sichere Notiz"
        ItemType.SshKey -> "SSH-Schlüssel"
    }

val ItemType.icon: ImageVector
    get() = when (this) {
        ItemType.Login -> Symbols.Key
        ItemType.Card -> Symbols.CreditCard
        ItemType.Identity -> Symbols.IdCard
        ItemType.Note -> Symbols.StickyNote
        ItemType.SshKey -> Symbols.Terminal
    }

val ItemType.polygon: RoundedPolygon
    get() = when (this) {
        ItemType.Login -> MaterialShapes.Cookie9Sided
        ItemType.Card -> MaterialShapes.Pill
        ItemType.Identity -> MaterialShapes.Clover4Leaf
        ItemType.Note -> MaterialShapes.Square
        ItemType.SshKey -> MaterialShapes.Sunny
    }

val ItemType.containerColor: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        ItemType.Login -> MaterialTheme.colorScheme.primaryContainer
        ItemType.Card -> MaterialTheme.colorScheme.tertiaryContainer
        ItemType.Identity -> MaterialTheme.colorScheme.secondaryContainer
        ItemType.Note -> MaterialTheme.colorScheme.surfaceContainerHighest
        ItemType.SshKey -> MaterialTheme.colorScheme.inverseSurface
    }

val ItemType.contentColor: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        ItemType.Login -> MaterialTheme.colorScheme.onPrimaryContainer
        ItemType.Card -> MaterialTheme.colorScheme.onTertiaryContainer
        ItemType.Identity -> MaterialTheme.colorScheme.onSecondaryContainer
        ItemType.Note -> MaterialTheme.colorScheme.onSurfaceVariant
        ItemType.SshKey -> MaterialTheme.colorScheme.inverseOnSurface
    }

/** Monogram in the item type's shape. */
@Composable
fun ItemAvatar(
    type: ItemType,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val shape = type.polygon.toShape()
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(type.containerColor),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString()
        if (initial == null) {
            Icon(type.icon, contentDescription = null, tint = type.contentColor)
        } else {
            Text(
                text = initial,
                color = type.contentColor,
                style = when {
                    size >= 80.dp -> MaterialTheme.typography.displaySmallEmphasized
                    size >= 56.dp -> MaterialTheme.typography.headlineSmallEmphasized
                    else -> MaterialTheme.typography.titleMediumEmphasized
                },
            )
        }
    }
}

/** Type icon in the type's shape, used where there is no name yet (new-item menu, filters). */
@Composable
fun ItemTypeBadge(type: ItemType, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(type.polygon.toShape())
            .background(type.containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(type.icon, contentDescription = null, tint = type.contentColor, modifier = Modifier.size(size * 0.5f))
    }
}
