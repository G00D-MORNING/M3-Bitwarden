package com.framepersecond.m3bitwarden.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.framepersecond.m3bitwarden.ThemeMode
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionExpirationDate
import platform.UIKit.UIPasteboardOptionLocalOnly
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow

actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    if (count == 0) return bytes
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, count.convert(), pinned.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return bytes
}

actual val supportsDynamicColor: Boolean = false

actual val biometricName: String = "Face ID"

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

@Composable
actual fun ApplySystemAppearance(themeMode: ThemeMode, dark: Boolean) {
    SideEffect {
        val style = when (themeMode) {
            ThemeMode.System -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            ThemeMode.Light -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ThemeMode.Dark -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
        }
        UIApplication.sharedApplication.windows.forEach { window ->
            (window as? UIWindow)?.overrideUserInterfaceStyle = style
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

private const val PLAIN_TEXT_UTI = "public.utf8-plain-text"
private const val SENSITIVE_CLIPBOARD_SECONDS = 90.0

@Composable
actual fun rememberClipboardWriter(): ClipboardWriter = remember {
    ClipboardWriter { text, sensitive ->
        val pasteboard = UIPasteboard.generalPasteboard
        if (sensitive) {
            pasteboard.setItems(
                listOf(mapOf(PLAIN_TEXT_UTI to text)),
                options = mapOf(
                    UIPasteboardOptionLocalOnly to true,
                    UIPasteboardOptionExpirationDate to NSDate.dateWithTimeIntervalSinceNow(SENSITIVE_CLIPBOARD_SECONDS),
                ),
            )
        } else {
            pasteboard.string = text
        }
    }
}
