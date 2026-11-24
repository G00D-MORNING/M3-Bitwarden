package com.framepersecond.m3bitwarden.platform

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.framepersecond.m3bitwarden.ThemeMode
import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

actual val supportsDynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

actual val biometricName: String = "Fingerabdruck"

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? {
    if (!supportsDynamicColor) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

@Composable
actual fun ApplySystemAppearance(themeMode: ThemeMode, dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun rememberClipboardWriter(): ClipboardWriter {
    val context = LocalContext.current
    return remember(context) {
        ClipboardWriter { text, sensitive ->
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@ClipboardWriter
            val clip = ClipData.newPlainText("M3 Bitwarden", text)
            if (sensitive) {
                val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ClipDescription.EXTRA_IS_SENSITIVE
                } else {
                    "android.content.extra.IS_SENSITIVE"
                }
                clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
            }
            clipboard.setPrimaryClip(clip)
        }
    }
}

/** Set once by the Android Application before any UI or service runs. */
object AndroidPlatform {
    lateinit var context: android.content.Context
}

actual object PlatformPrefs {
    private val prefs by lazy { AndroidPlatform.context.getSharedPreferences("m3b_settings", android.content.Context.MODE_PRIVATE) }

    actual fun getString(key: String): String? = prefs.getString(key, null)

    actual fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

actual fun openUrl(url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { AndroidPlatform.context.startActivity(intent) }
}
