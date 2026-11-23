package com.framepersecond.m3bitwarden.android.autofill

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Checks Digital Asset Links: a website lists the apps (package + signing certificate)
 * that may use its logins in `/.well-known/assetlinks.json`. A verified pair lets us fill an
 * app with the website's login without the user linking anything by hand.
 *
 * Only public files are fetched, results are cached for a week, and the whole lookup is
 * bounded so a slow site never delays the fill request.
 */
class AssetLinksVerifier(context: Context) {
    private val prefs = context.getSharedPreferences("m3b_assetlinks", Context.MODE_PRIVATE)
    private val pool = Executors.newFixedThreadPool(4)

    /** Domains among [candidates] that verifiably belong to [app]. */
    fun verifiedDomains(app: RequestingApp, candidates: Collection<String>, budgetMs: Long = 1_200): Set<String> {
        val cert = app.certSha256 ?: return emptySet()
        val todo = candidates.distinct().take(MAX_LOOKUPS)
        val tasks = todo.map { domain -> Callable { domain to isVerified(domain, app.packageName, cert) } }
        val results = runCatching { pool.invokeAll(tasks, budgetMs, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
        return results.mapNotNull { f -> runCatching { f.get() }.getOrNull()?.takeIf { it.second }?.first }.toSet()
    }

    private fun isVerified(domain: String, pkg: String, cert: String): Boolean {
        val key = "$domain|$pkg|$cert"
        val cached = prefs.getString(key, null)
        if (cached != null) {
            val (ok, ts) = cached.split('|').let { it[0].toBoolean() to it[1].toLong() }
            if (System.currentTimeMillis() - ts < CACHE_MS) return ok
        }
        val ok = runCatching { fetchAndCheck(domain, pkg, cert) }.getOrDefault(false)
        prefs.edit().putString(key, "$ok|${System.currentTimeMillis()}").apply()
        return ok
    }

    private fun fetchAndCheck(domain: String, pkg: String, cert: String): Boolean {
        val conn = URL("https://$domain/.well-known/assetlinks.json").openConnection() as HttpURLConnection
        conn.connectTimeout = 1_000
        conn.readTimeout = 1_000
        conn.instanceFollowRedirects = false // the spec forbids redirects for this file
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) return false
            val body = conn.inputStream.bufferedReader().use { it.readText().take(256_000) }
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val statement = arr.optJSONObject(i) ?: continue
                val relations = statement.optJSONArray("relation") ?: continue
                val grants = (0 until relations.length()).map { relations.optString(it) }
                if (grants.none { it == "delegate_permission/common.get_login_creds" || it == "delegate_permission/common.handle_all_urls" }) continue
                val target = statement.optJSONObject("target") ?: continue
                if (target.optString("namespace") != "android_app" || target.optString("package_name") != pkg) continue
                val prints = target.optJSONArray("sha256_cert_fingerprints") ?: continue
                if ((0 until prints.length()).any { prints.optString(it).equals(cert, ignoreCase = true) }) return true
            }
            return false
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_LOOKUPS = 6
        const val CACHE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
