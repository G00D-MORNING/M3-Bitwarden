package com.framepersecond.m3bitwarden.data

/**
 * Secure links: an item's fields, encrypted on this device as a Bitwarden Send on the user's
 * Vaultwarden and opened on [BASE_URL]. The key only lives in the part after "#", which browsers
 * never send to a server.
 */
object SecureLinks {
    const val BASE_URL = "https://passwort.jochenderrochen.de"

    /** How long a link stays valid (Vaultwarden allows at most 31 days). */
    enum class Validity(val label: String, val hours: Int) {
        Hour("1 Stunde", 1),
        Day("1 Tag", 24),
        Week("7 Tage", 24 * 7),
        Month("30 Tage", 24 * 30),
    }

    /** How often a link may be opened; 0 means no limit. */
    enum class Views(val label: String, val count: Int) {
        Once("Einmal", 1),
        Five("5-mal", 5),
        Unlimited("Unbegrenzt", 0),
    }

    /** The link for a send's access id and its base64url key seed. */
    fun url(accessId: String, key: String): String = "$BASE_URL/#/$accessId/$key"

    /** What the receiver sees: label/value pairs, without one-time-code secrets. */
    fun fields(item: VaultItem): List<Pair<String, String>> = buildList {
        item.login?.let { l ->
            l.allUris.forEachIndexed { i, u -> add((if (i == 0) "Website" else "Website ${i + 1}") to u) }
            add("Benutzername" to l.username)
            add("Passwort" to l.password)
        }
        item.card?.let { c ->
            add("Karteninhaber" to c.cardholder)
            add("Kartennummer" to c.number)
            val exp = listOf(c.expMonth, c.expYear).filter { it.isNotBlank() }.joinToString("/")
            add("Gültig bis" to exp)
            add("Prüfnummer" to c.code)
        }
        item.identity?.let { p ->
            add("Name" to p.fullName)
            add("Firma" to p.company)
            add("E-Mail" to p.email)
            add("Telefon" to p.phone)
            val address = listOf(
                p.address1, p.address2, p.address3,
                listOf(p.postalCode, p.city).filter { it.isNotBlank() }.joinToString(" "),
                p.state, p.country,
            ).filter { it.isNotBlank() }.joinToString("\n")
            add("Adresse" to address)
        }
        item.fields.filter { it.type != CustomFieldType.Linked }.forEach { add(it.name to it.value) }
        add("Notizen" to item.notes)
    }.filter { it.second.isNotBlank() }

    /** The send text: JSON the viewer shows as fields (see server/sendview/view.js). */
    fun content(item: VaultItem): String =
        fields(item).joinToString(",", prefix = "{\"m3b\":1,\"fields\":[", postfix = "]}") { (l, v) ->
            "{\"l\":${json(l)},\"v\":${json(v)}}"
        }

    private fun json(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }
}
