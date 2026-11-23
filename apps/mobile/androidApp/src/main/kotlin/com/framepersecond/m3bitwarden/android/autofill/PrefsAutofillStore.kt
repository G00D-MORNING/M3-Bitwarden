package com.framepersecond.m3bitwarden.android.autofill

import android.content.Context
import com.framepersecond.m3bitwarden.autofill.AutofillStore
import com.framepersecond.m3bitwarden.autofill.match.LearnedLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-local autofill memory in app-private storage. Holds only item ids, package names,
 * certificate fingerprints and domains: no usernames, no passwords.
 */
class PrefsAutofillStore(context: Context) : AutofillStore {
    private val prefs = context.getSharedPreferences("m3b_autofill", Context.MODE_PRIVATE)

    override fun learnedLinks(): List<LearnedLink> {
        val arr = JSONArray(prefs.getString(KEY_LINKS, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LearnedLink(
                credentialId = o.getString("c"),
                appId = o.optString("a").ifEmpty { null },
                appCertSha256 = o.optString("s").ifEmpty { null },
                domain = o.optString("d").ifEmpty { null },
            )
        }
    }

    override fun addLearnedLink(link: LearnedLink) {
        val existing = learnedLinks().filterNot { it.credentialId == link.credentialId && it.appId == link.appId && it.domain == link.domain }
        val arr = JSONArray()
        (existing + link).forEach { l ->
            arr.put(JSONObject().put("c", l.credentialId).put("a", l.appId ?: "").put("s", l.appCertSha256 ?: "").put("d", l.domain ?: ""))
        }
        prefs.edit().putString(KEY_LINKS, arr.toString()).apply()
    }

    override fun dismissed(): Set<String> = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

    override fun dismiss(key: String) {
        prefs.edit().putStringSet(KEY_DISMISSED, dismissed() + key).apply()
    }

    override fun usage(): Map<String, Pair<Long, Int>> {
        val o = JSONObject(prefs.getString(KEY_USAGE, "{}"))
        return o.keys().asSequence().associateWith { k ->
            val v = o.getJSONArray(k)
            v.getLong(0) to v.getInt(1)
        }
    }

    override fun recordUse(itemId: String, epochMs: Long) {
        val o = JSONObject(prefs.getString(KEY_USAGE, "{}"))
        val count = o.optJSONArray(itemId)?.optInt(1) ?: 0
        o.put(itemId, JSONArray().put(epochMs).put(count + 1))
        prefs.edit().putString(KEY_USAGE, o.toString()).apply()
    }

    private companion object {
        const val KEY_LINKS = "learned_links"
        const val KEY_DISMISSED = "dismissed"
        const val KEY_USAGE = "usage"
    }
}
