package com.framepersecond.m3bitwarden.autofill.text

/**
 * Normalisation used before keyword matching, so that "E-Mail-Adresse", "e_mail" and
 * "eMail" all become "emailadresse" / "email", and "Bestätigen" matches "bestaetigen".
 */
object TextNorm {

    private val transliteration: Map<Char, String> = buildMap {
        put('ä', "ae"); put('ö', "oe"); put('ü', "ue"); put('ß', "ss")
        "àáâãåā".forEach { put(it, "a") }
        "çćč".forEach { put(it, "c") }
        "èéêëēěę".forEach { put(it, "e") }
        "ìíîïī".forEach { put(it, "i") }
        "ñńň".forEach { put(it, "n") }
        "òóôõøō".forEach { put(it, "o") }
        "ùúûū".forEach { put(it, "u") }
        "ýÿ".forEach { put(it, "y") }
        "śšş".forEach { put(it, "s") }
        "źżž".forEach { put(it, "z") }
        put('ł', "l"); put('ř', "r"); put('ğ', "g"); put('ı', "i"); put('æ', "ae"); put('œ', "oe")
    }

    /** Lower-cases, transliterates and removes every separator: "E-Mail Adresse" → "emailadresse". */
    fun squash(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val sb = StringBuilder(input.length)
        for (c in input.lowercase()) {
            val t = transliteration[c]
            when {
                t != null -> sb.append(t)
                c.isLetterOrDigit() -> sb.append(c)
                else -> Unit
            }
        }
        return sb.toString()
    }

    /**
     * Splits into words on separators and camelCase: "loginPassword_field" → [login, password, field].
     * Each word is squashed.
     */
    fun words(input: String?): List<String> {
        if (input.isNullOrEmpty()) return emptyList()
        val spaced = StringBuilder(input.length + 8)
        var prev: Char? = null
        for (c in input) {
            if (prev != null && prev.isLowerCase() && c.isUpperCase()) spaced.append(' ')
            spaced.append(c)
            prev = c
        }
        return spaced.split(' ', '-', '_', '.', '/', ':', '[', ']', '(', ')', '\t', '\n', ',', ';', '|')
            .map(::squash)
            .filter { it.isNotEmpty() }
    }
}
