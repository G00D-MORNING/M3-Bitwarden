package com.framepersecond.m3bitwarden

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.platform.PlatformBackHandler
import com.framepersecond.m3bitwarden.platform.rememberClipboardWriter
import com.framepersecond.m3bitwarden.ui.LocalCopy
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import com.framepersecond.m3bitwarden.ui.screens.DetailScreen
import com.framepersecond.m3bitwarden.ui.screens.EditScreen
import com.framepersecond.m3bitwarden.ui.screens.FoldersScreen
import com.framepersecond.m3bitwarden.ui.screens.GeneratorScreen
import com.framepersecond.m3bitwarden.ui.screens.SettingsScreen
import com.framepersecond.m3bitwarden.ui.screens.VaultScreen
import com.framepersecond.m3bitwarden.ui.theme.M3BitwardenTheme
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
            if (!VaultSession.isUnlocked) {
                DemoLock()
            } else {
                VaultApp(platformSettings)
            }
        }
    }
}

@Composable
private fun DemoLock() {
    val scope = rememberCoroutineScope()
    val state = remember { AppState() }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Icon(Symbols.ShieldLockFill, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Text("Tresor gesperrt", style = MaterialTheme.typography.headlineMediumEmphasized)
            if (VaultSession.backend == null) {
                Text("Demo-Modus mit Beispieldaten", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Button(onClick = { scope.launch { state.demoUnlock() } }) { Text("Entsperren") }
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

    CompositionLocalProvider(LocalCopy provides copy) {
        Scaffold(
            snackbarHost = { SnackbarHost(state.snackbar) },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { _ ->
            val top = state.backStack.lastOrNull()
            AnimatedContent(
                targetState = top,
                transitionSpec = {
                    val forward = targetState != null && (initialState == null || state.backStack.size > 1 || initialState !is Route)
                    if (forward) {
                        (slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))) togetherWith fadeOut(tween(150))
                    } else {
                        fadeIn(tween(250)) togetherWith (slideOutHorizontally(tween(250)) { it / 4 } + fadeOut(tween(250)))
                    }
                },
                label = "route",
            ) { route ->
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

@Composable
private fun Tabs(state: AppState, platformSettings: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val padding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding())
        AnimatedContent(state.selectedTab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "tab") { tab ->
            when (tab) {
                MainTab.Vault -> VaultScreen(state, padding)
                MainTab.Generator -> GeneratorScreen(state, padding)
                MainTab.Settings -> SettingsScreen(state, padding, platformSettings)
            }
        }
        FloatingNav(state, Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding() + 16.dp))
    }
}

/** Floating, expanding navigation bar: the selected tab grows into a pill with its label. */
@Composable
private fun FloatingNav(state: AppState, modifier: Modifier) {
    Surface(
        modifier = modifier,
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
                Row(
                    Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { state.selectedTab = tab }
                        .animateContentSize()
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
