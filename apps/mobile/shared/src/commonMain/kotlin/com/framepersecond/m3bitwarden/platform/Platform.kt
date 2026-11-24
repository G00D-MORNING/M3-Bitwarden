package com.framepersecond.m3bitwarden.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import com.framepersecond.m3bitwarden.ThemeMode

/** Cryptographically secure random bytes from the operating system. */
expect fun secureRandomBytes(count: Int): ByteArray

/** Whether the OS offers wallpaper-based colors (Android 12+). */
expect val supportsDynamicColor: Boolean

/** Label for the biometric unlock button, e.g. "Face ID" on iPhone. */
expect val biometricName: String

/** Wallpaper-based color scheme, or null where the platform has none. */
@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

/** Keeps status bar and system UI legible when the user forces light or dark mode. */
@Composable
expect fun ApplySystemAppearance(themeMode: ThemeMode, dark: Boolean)

/** System back (Android back gesture). iOS uses the in-app back buttons. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

fun interface ClipboardWriter {
    /**
     * Copies [text]. [sensitive] values are hidden from clipboard previews on Android 13+
     * and expire after 90 seconds on iOS.
     */
    fun copy(text: String, sensitive: Boolean)
}

@Composable
expect fun rememberClipboardWriter(): ClipboardWriter

/** Uniform random int in [0, bound) without modulo bias. */
fun secureRandomInt(bound: Int): Int {
    require(bound > 0)
    val limit = UInt.MAX_VALUE - (UInt.MAX_VALUE % bound.toUInt()) - 1u
    while (true) {
        val b = secureRandomBytes(4)
        val value = (b[0].toUInt() and 0xFFu) or
            ((b[1].toUInt() and 0xFFu) shl 8) or
            ((b[2].toUInt() and 0xFFu) shl 16) or
            ((b[3].toUInt() and 0xFFu) shl 24)
        if (value <= limit) return (value % bound.toUInt()).toInt()
    }
}

/** Small non-secret preferences (theme, timeout). iOS: shared App Group defaults; Android: SharedPreferences. */
expect object PlatformPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** Opens a website in the system browser. */
expect fun openUrl(url: String)
