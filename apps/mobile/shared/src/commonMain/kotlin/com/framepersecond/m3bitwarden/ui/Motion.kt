package com.framepersecond.m3bitwarden.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import kotlinx.coroutines.delay

/*
 * Motion of the vault UI, following M3 Expressive: springs instead of fixed curves, elements that
 * travel between screens instead of being replaced, and small confirmations on the spot.
 */

/** Scope of the list ↔ item transition, provided by the app shell. Null in previews and sheets. */
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalRouteScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Expressive spatial spring for elements that fly between screens. */
private val heroBounds = BoundsTransform { _, _ -> spring<Rect>(dampingRatio = 0.82f, stiffness = 340f) }

/**
 * The element keeps its identity across the list → item transition: a logo tile grows into the
 * item's emblem, the name into the page title. No-op where there is no transition.
 */
@Composable
fun Modifier.hero(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val route = LocalRouteScope.current ?: return this
    return with(shared) {
        this@hero.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = route,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            boundsTransform = heroBounds,
        )
    }
}

/**
 * Rises into place the first time [key] is shown (staggered by [index]), not again when the
 * list comes back from an item page. [seen] lives as long as the unlocked session.
 */
@Composable
fun Modifier.riseIn(key: Any, index: Int, seen: MutableSet<Any>): Modifier {
    val first = remember(key) { seen.add(key) }
    val progress = remember(key) { Animatable(if (first) 0f else 1f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            if (index < STAGGER_ROWS) delay(index * STAGGER_MS)
            progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 260f))
        }
    }
    val distance = with(LocalDensity.current) { 32.dp.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * distance
        val s = 0.94f + 0.06f * p
        scaleX = s
        scaleY = s
    }
}

private const val STAGGER_ROWS = 12
private const val STAGGER_MS = 34L

/** Copy button that turns into a check for a moment, so the copy is confirmed where you tapped. */
@Composable
fun CopyButton(contentDescription: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(done) {
        if (done) {
            delay(1_400)
            done = false
        }
    }
    val container by animateColorAsState(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, spring(stiffness = Spring.StiffnessMediumLow))
    val content by animateColorAsState(if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, spring(stiffness = Spring.StiffnessMediumLow))
    FilledTonalIconButton(
        onClick = { onCopy(); done = true },
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = container, contentColor = content),
        modifier = modifier,
    ) {
        AnimatedContent(
            done,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.45f, stiffness = 500f), initialScale = 0.3f) + fadeIn()) togetherWith
                    (scaleOut(targetScale = 0.3f) + fadeOut(tween(90)))
            },
            label = "copied",
        ) { copied ->
            Icon(if (copied) Symbols.Check else Symbols.ContentCopy, if (copied) "Kopiert" else contentDescription)
        }
    }
}

/** Text whose characters roll like a counter when they change (verification codes). */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { index, char ->
            AnimatedContent(
                char,
                transitionSpec = {
                    val delayMs = index * 28
                    (slideInVertically(spring(dampingRatio = 0.7f, stiffness = 380f)) { -it } + fadeIn(tween(160, delayMs))) togetherWith
                        (slideOutVertically(tween(180, delayMs)) { it } + fadeOut(tween(120, delayMs))) using SizeTransform(clip = true)
                },
                label = "digit$index",
            ) { c -> Text(c.toString(), style = style, color = color, maxLines = 1, softWrap = false) }
        }
    }
}

/** [value] with its first [shown] characters visible and the rest masked, for the reveal sweep. */
fun revealText(value: String, shown: Int, styled: (String) -> AnnotatedString = { AnnotatedString(it) }): AnnotatedString =
    buildAnnotatedString {
        append(styled(value.take(shown)))
        append("•".repeat((value.length - shown).coerceAtLeast(0)))
    }
