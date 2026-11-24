package com.framepersecond.m3bitwarden.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.generator.PasswordGenerator
import com.framepersecond.m3bitwarden.generator.Strength
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import com.framepersecond.m3bitwarden.ui.theme.PasswordColors
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Rounded group of rows, the building block of detail and settings pages. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 8.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** Passwords with digits and symbols tinted, so look-alike characters can be told apart. */
@Composable
fun colorizedPassword(text: String): AnnotatedString {
    val digit = PasswordColors.digit
    val symbol = PasswordColors.symbol
    return remember(text, digit, symbol) {
        buildAnnotatedString {
            text.forEach { c ->
                when {
                    c.isDigit() -> withStyle(SpanStyle(color = digit)) { append(c) }
                    !c.isLetterOrDigit() -> withStyle(SpanStyle(color = symbol)) { append(c) }
                    else -> append(c)
                }
            }
        }
    }
}

/**
 * One labelled value with copy (and for secrets, reveal) actions.
 *
 * @param secret hides the value behind dots until revealed.
 * @param password tints digits/symbols when revealed.
 */
@Composable
fun FieldRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    secret: Boolean = false,
    password: Boolean = false,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    onCopy: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    var revealed by remember(value) { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val style = if (monospace || (secret && revealed) || password) {
                MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyLarge
            }
            when {
                secret && !revealed -> Text("•".repeat(value.length.coerceIn(8, 16)), style = style)
                password -> Text(colorizedPassword(value), style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
                else -> Text(value, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            }
        }
        if (secret) {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(if (revealed) Symbols.VisibilityOff else Symbols.Visibility, if (revealed) "Verbergen" else "Anzeigen")
            }
        }
        trailing?.invoke()
        if (onCopy != null) {
            IconButton(onClick = onCopy) { Icon(Symbols.ContentCopy, "$label kopieren") }
        }
    }
}

/** Ticks once per second while shown. */
@OptIn(ExperimentalTime::class)
@Composable
fun rememberEpochSeconds(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().epochSeconds) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000 - (Clock.System.now().toEpochMilliseconds() % 1_000))
            now = Clock.System.now().epochSeconds
        }
    }
    return now
}

/** Verification code with a countdown ring. [code] is null when it cannot be computed. */
@Composable
fun TotpRow(code: String?, period: Int, now: Long, onCopy: () -> Unit) {
    val left = period - (now % period).toInt()
    val progress by animateFloatAsState(left / period.toFloat())
    val urgent = left <= 5
    val color by animateColorAsState(if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = code != null, onClick = onCopy).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Symbols.LockClock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Bestätigungscode (TOTP)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                code?.let { if (it.length == 6) it.take(3) + " " + it.drop(3) else it } ?: "Ungültiger Schlüssel",
                style = MaterialTheme.typography.headlineSmallEmphasized.copy(fontFamily = FontFamily.Monospace),
                color = if (code == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
            CircularProgressIndicator(
                progress = { progress },
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.size(34.dp),
            )
            Text("$left", style = MaterialTheme.typography.labelMedium, color = color)
        }
        IconButton(onClick = onCopy, enabled = code != null) { Icon(Symbols.ContentCopy, "Code kopieren") }
    }
}

val Strength.label: String
    get() = when (this) {
        Strength.Weak -> "Schwach"
        Strength.Fair -> "Mittel"
        Strength.Strong -> "Stark"
        Strength.VeryStrong -> "Sehr stark"
    }

@Composable
fun strengthColor(strength: Strength): Color = when (strength) {
    Strength.Weak -> MaterialTheme.colorScheme.error
    Strength.Fair -> Color(0xFFE8A317)
    Strength.Strong -> MaterialTheme.colorScheme.tertiary
    Strength.VeryStrong -> MaterialTheme.colorScheme.tertiary
}

/** Strength meter for a password or generator setting. */
@Composable
fun StrengthMeter(bits: Double, modifier: Modifier = Modifier) {
    val strength = PasswordGenerator.strength(bits)
    val color = strengthColor(strength)
    val progress by animateFloatAsState(PasswordGenerator.progress(bits))
    Column(modifier) {
        LinearProgressIndicator(
            progress = { progress },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(strength.label, style = MaterialTheme.typography.labelMedium, color = color)
            Text("≈ ${PasswordGenerator.formatBits(bits)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Small tonal pill, e.g. "Geteilt" or a folder name. */
@Composable
fun InfoChip(text: String, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
