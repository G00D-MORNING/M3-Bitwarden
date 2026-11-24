package com.framepersecond.m3bitwarden.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.framepersecond.m3bitwarden.AppSettings
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.generator.PasswordGenerator
import com.framepersecond.m3bitwarden.generator.Strength
import com.framepersecond.m3bitwarden.ui.brands.Brands

/** Corner radii of a connected list: big outside, small between rows. */
fun segmentShape(index: Int, count: Int, outer: Dp = 16.dp, inner: Dp = 4.dp): Shape = when {
    count <= 1 -> RoundedCornerShape(outer)
    index == 0 -> RoundedCornerShape(outer, outer, inner, inner)
    index == count - 1 -> RoundedCornerShape(inner, inner, outer, outer)
    else -> RoundedCornerShape(inner)
}

/** Section heading in primary, like "Favoriten" or "Sicherheit". */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp).defaultMinSize(minHeight = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Logo tile: the brand's logo in its colors, else the item type's icon in its tonal colors. */
@Composable
fun ItemTile(item: VaultItem, size: Dp = 44.dp, corner: Dp = 14.dp, modifier: Modifier = Modifier) {
    val brand = if (AppSettings.showLogos) Brands.forItem(item) else null
    val bg = brand?.let { Color(it.bg) } ?: item.type.containerColor
    val fg = brand?.let { Color(it.fg) } ?: item.type.contentColor
    Box(modifier.size(size).clip(RoundedCornerShape(corner)).background(bg), contentAlignment = Alignment.Center) {
        if (brand != null) {
            Icon(Brands.icon(brand), null, tint = fg, modifier = Modifier.size(size / 2))
        } else {
            Icon(item.type.icon, null, tint = fg, modifier = Modifier.size(size / 2))
        }
    }
}

/** A Material shape that turns slowly, the prototype's "alive" emblem. */
@Composable
fun SpinningShape(
    polygon: RoundedPolygon,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (busy) 1_400 else 30_000, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    val shape = polygon.toShape()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle }.clip(shape).background(color))
        content()
    }
}

/** Strength label, bits and a wavy bar, as on the detail, edit and generator screens. */
@Composable
fun WavyStrength(bits: Double, modifier: Modifier = Modifier, thick: Boolean = false, showLabel: Boolean = true) {
    val strength = PasswordGenerator.strength(bits)
    val color = when (strength) {
        Strength.Weak -> MaterialTheme.colorScheme.error
        Strength.Fair -> MaterialTheme.colorScheme.onSurfaceVariant
        Strength.Strong -> MaterialTheme.colorScheme.primary
        Strength.VeryStrong -> MaterialTheme.colorScheme.tertiary
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showLabel) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(strength.label, style = if (thick) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.labelLargeEmphasized, color = color, modifier = Modifier.weight(1f))
                Text("≈ ${PasswordGenerator.formatBits(bits)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LinearWavyProgressIndicator(
            progress = { PasswordGenerator.progress(bits) },
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Circular wavy progress with a centred label (security score, TOTP countdown). */
@Composable
fun WavyRing(progress: Float, label: String, size: Dp, color: Color = MaterialTheme.colorScheme.primary, textColor: Color = MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        CircularWavyProgressIndicator(progress = { progress }, color = color, modifier = Modifier.size(size))
        Text(label, style = MaterialTheme.typography.labelLargeEmphasized, color = textColor)
    }
}

data class GroupOption(val label: String, val icon: ImageVector? = null, val selected: Boolean = false, val onClick: () -> Unit)

/** Connected button group (M3 Expressive): tonal segments, the selected one turns into a pill. */
@Composable
fun ConnectedGroup(options: List<GroupOption>, modifier: Modifier = Modifier, fill: Boolean = true) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            val shapes = when {
                options.size == 1 -> ToggleButtonDefaults.shapes()
                index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = option.selected,
                onCheckedChange = { option.onClick() },
                shapes = shapes,
                colors = ToggleButtonDefaults.tonalToggleButtonColors(),
                modifier = if (fill) Modifier.weight(1f) else Modifier,
            ) {
                if (option.icon != null) {
                    // Icon changes (star ↔ filled star) pop with a bouncy spring.
                    AnimatedContent(
                        option.icon,
                        transitionSpec = {
                            (scaleIn(spring(dampingRatio = 0.4f, stiffness = 550f), initialScale = 0.4f) + fadeIn()) togetherWith
                                (scaleOut(targetScale = 0.4f) + fadeOut(tween(90)))
                        },
                        label = "groupIcon",
                    ) { icon -> Icon(icon, null, Modifier.size(18.dp)) }
                    if (option.label.isNotEmpty()) Spacer(Modifier.width(8.dp))
                }
                if (option.label.isNotEmpty()) Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One row of a segmented list: tonal leading icon, headline, supporting text, trailing slot. */
@Composable
fun ListRow(
    headline: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    iconBg: Color = MaterialTheme.colorScheme.secondaryContainer,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(if (destructive) MaterialTheme.colorScheme.errorContainer else iconBg), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.onErrorContainer else iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    destructive -> MaterialTheme.colorScheme.error
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (!supporting.isNullOrEmpty()) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(headline: String, checked: Boolean, shape: Shape, supporting: String? = null, icon: ImageVector? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListRow(headline, shape, supporting = supporting, icon = icon, enabled = enabled, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Grouped list helper: [rows] get connected corners automatically. */
@Composable
fun SegmentedList(modifier: Modifier = Modifier, content: SegmentedListScope.() -> Unit) {
    val scope = SegmentedListScope().apply(content)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        scope.rows.forEachIndexed { i, row -> row(segmentShape(i, scope.rows.size)) }
    }
}

class SegmentedListScope {
    internal val rows = mutableListOf<@Composable (Shape) -> Unit>()
    fun row(content: @Composable (Shape) -> Unit) {
        rows += content
    }
}

/** A white "box" section like the editor's, with 16 dp padding and spacing. */
@Composable
fun BoxSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

val Polygons = object {
    val cookie12: RoundedPolygon get() = MaterialShapes.Cookie12Sided
    val flower: RoundedPolygon get() = MaterialShapes.Flower
}
