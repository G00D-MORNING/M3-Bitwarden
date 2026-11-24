package com.framepersecond.m3bitwarden

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.platform.PlatformBackHandler
import com.framepersecond.m3bitwarden.platform.rememberClipboardWriter
import com.framepersecond.m3bitwarden.ui.LocalCopy
import com.framepersecond.m3bitwarden.ui.LocalRouteScope
import com.framepersecond.m3bitwarden.ui.LocalSharedScope
import com.framepersecond.m3bitwarden.ui.components.SpinningShape
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import com.framepersecond.m3bitwarden.ui.screens.DetailScreen
import com.framepersecond.m3bitwarden.ui.screens.EditScreen
import com.framepersecond.m3bitwarden.ui.screens.FoldersScreen
import com.framepersecond.m3bitwarden.ui.screens.GeneratorScreen
import com.framepersecond.m3bitwarden.ui.screens.SettingsScreen
import com.framepersecond.m3bitwarden.ui.screens.VaultScreen
import com.framepersecond.m3bitwarden.ui.theme.M3BitwardenTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The vault app: tabs for vault, generator and settings, with item screens on top.
 *
 * With a connected backend, signing in and unlocking are handled by the platform before this is
 * shown (iOS: SwiftUI, Android: Compose screens in the app module); without one it runs on
 * demo data behind a demo lock screen.
 *
 * @param platformSettings extra rows in the autofill settings (Android: service status).
 */
@Composable
fun App(platformSettings: @Composable () -> Unit = {}) {
    M3BitwardenTheme(themeMode = AppSettings.themeMode, dynamicColor = AppSettings.dynamicColor) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            AnimatedContent(
                VaultSession.isUnlocked,
                transitionSpec = {
                    if (targetState) {
                        (fadeIn(tween(320, delayMillis = 60)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 300f), initialScale = 1.06f)) togetherWith
                            (fadeOut(tween(180)) + scaleOut(targetScale = 0.9f))
                    } else {
                        fadeIn(tween(260)) togetherWith fadeOut(tween(200))
                    }
                },
                label = "lock",
            ) { unlocked ->
                if (unlocked) VaultApp(platformSettings) else DemoLock()
            }
        }
    }
}

@Composable
private fun DemoLock() {
    val scope = rememberCoroutineScope()
    val state = remember { AppState() }
    var unlocking by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            SpinningShape(MaterialShapes.Cookie12Sided, MaterialTheme.colorScheme.primaryContainer, 144.dp, busy = unlocking) {
                Icon(if (unlocking) Symbols.Lock else Symbols.ShieldLockFill, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
            }
            Text(if (unlocking) "Wird entsperrt" else "Tresor gesperrt", style = MaterialTheme.typography.headlineMediumEmphasized)
            if (VaultSession.backend == null) {
                Text("Demo-Modus mit Beispieldaten", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Button(onClick = { unlocking = true; scope.launch { state.demoUnlock() } }, enabled = !unlocking) { Text("Entsperren") }
            }
        }
    }
}

@Composable
private fun VaultApp(platformSettings: @Composable () -> Unit) {
    val state = remember { AppState() }
    val scope = rememberCoroutineScope()
    val clipboard = rememberClipboardWriter()
    val copy: (String, String, Boolean) -> Unit = remember(clipboard) {
        { label, text, sensitive ->
            VaultSession.backend?.toClipboard(text, sensitive) ?: clipboard.copy(text, sensitive)
            scope.launch {
                state.snackbar.currentSnackbarData?.dismiss()
                state.snackbar.showSnackbar("$label kopiert")
            }
        }
    }

    LaunchedEffect(VaultSession.message) {
        val message = VaultSession.message ?: return@LaunchedEffect
        VaultSession.message = null
        state.snackbar.showSnackbar(message)
    }

    PlatformBackHandler(enabled = state.backStack.isNotEmpty() || state.selectedTab != MainTab.Vault) {
        if (!state.back()) state.selectedTab = MainTab.Vault
    }

    if (NativeChrome.enabled) NativeChromeBridge(state)

    CompositionLocalProvider(LocalCopy provides copy) {
        Scaffold(
            snackbarHost = {
                // Above the floating bars.
                SnackbarHost(state.snackbar, Modifier.padding(bottom = 84.dp))
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { _ ->
            SharedTransitionLayout {
                CompositionLocalProvider(LocalSharedScope provides this) {
                    val top = state.backStack.lastOrNull()
                    AnimatedContent(
                        targetState = top,
                        transitionSpec = {
                            val forward = targetState != null && (initialState == null || state.backStack.size > 1 || initialState !is Route)
                            if (forward) {
                                (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { it / 5 } + fadeIn(tween(260, delayMillis = 40))) togetherWith
                                    fadeOut(tween(140))
                            } else {
                                fadeIn(tween(240)) togetherWith (slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { it / 5 } + fadeOut(tween(200)))
                            }
                        },
                        label = "route",
                    ) { route ->
                        CompositionLocalProvider(LocalRouteScope provides this) {
                            when (route) {
                                null -> Tabs(state, platformSettings)
                                is Route.Detail -> DetailScreen(state, route.id)
                                is Route.Edit -> EditScreen(state, route.id, route.type)
                                Route.Folders -> FoldersScreen(state)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Feeds the native (Liquid Glass) bars on iOS and takes their taps. */
@Composable
private fun NativeChromeBridge(state: AppState) {
    DisposableEffect(state) {
        NativeChrome.commands = NativeChrome.Commands(
            selectTab = { tab ->
                if (tab == state.selectedTab) state.chromeCompact = false
                state.selectedTab = tab
            },
            newItem = { type -> state.open(Route.Edit(null, type)) },
        )
        onDispose {
            NativeChrome.commands = null
            NativeChrome.onChange?.invoke(ChromeSnapshot.Hidden)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow {
            val toolbar = NativeChrome.toolbar
            val onTabs = state.backStack.isEmpty()
            ChromeSnapshot(
                tab = state.selectedTab.ordinal,
                onTabs = onTabs,
                compact = state.chromeCompact,
                modal = NativeChrome.modalCount > 0,
                actions = if (!onTabs && state.backStack.last() is Route.Detail) toolbar?.actions.orEmpty() else emptyList(),
                primary = if (!onTabs && state.backStack.last() is Route.Detail) toolbar?.primary else null,
                itemTypes = NativeChrome.itemTypes,
            )
        }.distinctUntilChanged().collect { NativeChrome.onChange?.invoke(it) }
    }
}

@Composable
private fun Tabs(state: AppState, platformSettings: @Composable () -> Unit) {
    // Scrolling down tucks the floating bars away, scrolling up brings them back.
    val hideOnScroll = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) state.chromeCompact = true else if (available.y > 6f) state.chromeCompact = false
                return Offset.Zero
            }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).nestedScroll(hideOnScroll)) {
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val padding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding())
        AnimatedContent(
            state.selectedTab,
            transitionSpec = {
                // M3 fade-through: the old tab fades out quickly, the new one grows in.
                (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.94f)) togetherWith fadeOut(tween(90))
            },
            label = "tab",
        ) { tab ->
            when (tab) {
                MainTab.Vault -> VaultScreen(state, padding)
                MainTab.Generator -> GeneratorScreen(state, padding)
                MainTab.Settings -> SettingsScreen(state, padding, platformSettings)
            }
        }
        // Content scrolls under the status bar: fade it out there instead of cutting through the clock.
        val scrim = MaterialTheme.colorScheme.surfaceContainer
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(insets.calculateTopPadding() + 20.dp)
                .background(Brush.verticalGradient(0f to scrim, 0.6f to scrim.copy(alpha = 0.92f), 1f to Color.Transparent)),
        )
        if (!NativeChrome.enabled) {
            AnimatedVisibility(
                visible = !state.chromeCompact,
                enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 420f)) { it * 2 } + fadeIn(),
                exit = slideOutVertically(spring(stiffness = 520f)) { it * 2 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding() + 16.dp),
            ) {
                FloatingNav(state)
            }
        }
    }
}

/** Floating, expanding navigation bar: the selected tab grows into a pill with its label. */
@Composable
private fun FloatingNav(state: AppState) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Triple(MainTab.Vault, "Tresor", Symbols.ShieldLock to Symbols.ShieldLockFill),
                Triple(MainTab.Generator, "Generator", Symbols.Password to Symbols.PasswordFill),
                Triple(MainTab.Settings, "Einstellungen", Symbols.Settings to Symbols.SettingsFill),
            ).forEach { (tab, label, icons) ->
                val selected = state.selectedTab == tab
                val pill by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, spring(stiffness = 500f))
                Row(
                    Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(pill)
                        .clickable { state.selectedTab = tab }
                        .animateContentSize(spring(dampingRatio = 0.65f, stiffness = 420f))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (selected) icons.second else icons.first, label, tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                    }
                }
            }
        }
    }
}
