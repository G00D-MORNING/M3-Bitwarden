package com.framepersecond.m3bitwarden.autofill.format

/**
 * Splits a stored phone number for forms with separate boxes ("+49" | "089" | "1234567").
 * Uses the user's own spacing when present ("+49 89 1234567"); otherwise the national
 * conventions of the most common countries.
 */
object PhoneSplitter {
    data class Parts(val countryCode: String?, val areaCode: String?, val local: String)

    private val COUNTRY_CODES = listOf("1", "7", "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46", "47", "48", "49", "351", "352", "353", "354", "358", "359", "370", "371", "372", "385", "386", "420", "421", "423")

    fun split(raw: String): Parts {
        val text = raw.trim()
        val groups = text.split(Regex("[\\s/()\\-.]+")).filter { it.isNotEmpty() }
        val international = text.startsWith("+") || text.startsWith("00")
        var digits = text.filter { it.isDigit() }
        var cc: String? = null
        if (international) {
            if (text.startsWith("00")) digits = digits.drop(2)
            val firstGroup = groups.firstOrNull()?.filter { it.isDigit() }?.removePrefix("00")
            cc = if (firstGroup != null && firstGroup in COUNTRY_CODES && groups.size > 1) firstGroup
            else COUNTRY_CODES.sortedByDescending { it.length }.firstOrNull { digits.startsWith(it) }
            if (cc != null) digits = digits.removePrefix(cc)
        }
        // Area code: the user's grouping wins; else NANP 3 digits, else German-style mobile/landline guesses.
        val nationalGroups = if (international) groups.drop(1) else groups
        val area: String? = when {
            nationalGroups.size >= 2 -> nationalGroups.first().filter { it.isDigit() }.let { if (!international && it.startsWith("0")) it else if (cc == "1") it else "0" + it.removePrefix("0") }
            cc == "1" && digits.length == 10 -> digits.take(3)
            cc == null && digits.length == 10 && !digits.startsWith("0") -> digits.take(3)
            digits.removePrefix("0").let { it.startsWith("15") || it.startsWith("16") || it.startsWith("17") } -> "0" + digits.removePrefix("0").take(if (digits.removePrefix("0").startsWith("152")) 4 else 3) // German mobile: 0151, 01520…
            else -> null
        }
        val areaDigits = area?.removePrefix("0")
        val local = if (areaDigits != null) digits.removePrefix("0").removePrefix(areaDigits) else digits
        return Parts(cc, area?.let { if (cc == "1") it.removePrefix("0") else it }, local)
    }
}
