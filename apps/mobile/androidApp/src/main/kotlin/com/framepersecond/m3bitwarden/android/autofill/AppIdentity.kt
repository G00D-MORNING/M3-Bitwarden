package com.framepersecond.m3bitwarden.android.autofill

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/** Who is asking: package, signing certificate, label, and whether it is a browser we trust. */
data class RequestingApp(
    val packageName: String,
    val certSha256: String?,
    val label: String,
    val isTrustedBrowser: Boolean,
)

object AppIdentity {

    /** Browsers whose reported web domain we trust (list based on Proton Pass, GPL-3.0). */
    private val BROWSERS = setOf(
        "alook.browser", "alook.browser.google", "app.vanadium.browser", "com.aloha.browser", "com.alohamobile.browser",
        "com.amazon.cloud9", "com.android.browser", "com.android.chrome", "com.avast.android.secure.browser",
        "com.avg.android.secure.browser", "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_default",
        "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "com.duckduckgo.mobile.android", "com.ecosia.android",
        "com.google.android.apps.chrome", "com.kiwibrowser.browser", "com.microsoft.emmx", "com.microsoft.emmx.beta",
        "com.naver.whale", "com.opera.browser", "com.opera.browser.beta", "com.opera.gx", "com.opera.mini.native",
        "com.opera.touch", "com.qwant.liberty", "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
        "com.startpage.app", "com.stoutner.privacybrowser.free", "com.stoutner.privacybrowser.standard",
        "com.vivaldi.browser", "com.vivaldi.browser.snapshot", "com.yandex.browser", "io.github.forkmaintainers.iceraven",
        "mark.via", "mark.via.gp", "net.waterfox.android.release", "org.adblockplus.browser", "org.bromite.bromite",
        "org.chromium.chrome", "org.cromite.cromite", "org.gnu.icecat", "org.ironfoxoss.ironfox", "org.mozilla.fenix",
        "org.mozilla.fennec_fdroid", "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus",
        "org.mozilla.klar", "org.torproject.torbrowser", "org.ungoogled.chromium.stable", "us.spotco.fennec_dos",
        "com.google.android.captiveportallogin",
    )

    /** Stores that ship the real browsers. A sideloaded "com.android.chrome" is not trusted. */
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending", "com.google.android.packageinstaller", "com.sec.android.app.samsungapps",
        "com.huawei.appmarket", "org.fdroid.fdroid", "com.aurora.store", "com.amazon.venezia",
    )

    fun of(context: Context, packageName: String): RequestingApp {
        val pm = context.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
        val cert = certSha256(pm, packageName)
        val trusted = packageName in BROWSERS && isFromTrustedSource(pm, packageName)
        return RequestingApp(packageName, cert, label, trusted)
    }

    private fun isFromTrustedSource(pm: PackageManager, packageName: String): Boolean = runCatching {
        val info = pm.getApplicationInfo(packageName, 0)
        val system = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        val installer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
        system || installer in TRUSTED_INSTALLERS
    }.getOrDefault(false)

    fun certSha256(pm: PackageManager, packageName: String): String? = runCatching {
        val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return null
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        val first = certs?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(first.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}
