package com.framepersecond.m3bitwarden.autofill.format

/**
 * Postal code and city from identity data that does not always keep them apart: imports
 * (Proton Pass, browsers, CSV) often leave the postal code empty and put "12345 Berlin" into the
 * city, or put the whole "Musterstr. 1, 12345 Berlin" into the address line.
 *
 * Recognised codes: DE/FR/IT/ES (5 digits), AT/CH/BE/DK/NO (4 digits), NL ("1234 AB"),
 * PL ("00-950"), PT ("1000-001"), SE ("123 45"), UK ("SW1A 1AA"), US ZIP ("12345-6789"),
 * optionally with a country prefix ("D-12345", "CH-8001").
 */
object PostalSplitter {
    data class Place(val postalCode: String, val city: String)

    private const val PREFIX = "(?:[A-Z]{1,2}\\s?-\\s?)?"
    private const val CODE =
        "(\\d{4}\\s?[A-Z]{2}|\\d{2}-\\d{3}|\\d{4}-\\d{3}|\\d{3}\\s\\d{2}|\\d{5}(?:-\\d{4})?|\\d{4}|[A-Z]{1,2}\\d[A-Z\\d]?\\s?\\d[A-Z]{2})"
    private val CODE_FIRST = Regex("^$PREFIX$CODE[\\s,]+(\\D.*)$")
    private val CODE_LAST = Regex("^(\\D.*?)[\\s,]+$PREFIX$CODE$")

    /** Splits "12345 Berlin", "D-12345 Berlin", "Berlin 12345", "SW1A 1AA London"; null if no code. */
    fun split(text: String): Place? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        CODE_FIRST.find(t)?.let { return Place(it.groupValues[1], it.groupValues[2].trim().trimEnd(',')) }
        CODE_LAST.find(t)?.let { return Place(it.groupValues[2], it.groupValues[1].trim().trimEnd(',')) }
        return null
    }

    /** Postal code and city, from their own fields if set, else found in the city or an address line. */
    fun resolve(postalCode: String, city: String, addressLines: List<String>): Place {
        val code = postalCode.trim()
        val town = city.trim()
        if (code.isNotEmpty()) {
            // The city may still carry the code ("12345 Berlin" with postal code "12345").
            val inner = split(town)
            return Place(code, if (inner != null && inner.postalCode.replace(" ", "") == code.replace(" ", "")) inner.city else town)
        }
        split(town)?.let { return it }
        for (line in addressLines) {
            // "Musterstr. 1, 12345 Berlin": only the part after a comma, code first, counts, so a
            // house number ("Musterstraße 1234") is never taken for a postal code.
            if (',' !in line) continue
            val tail = line.substringAfterLast(',').trim()
            val m = CODE_FIRST.find(tail.replace(Regex("\\s+"), " ")) ?: continue
            val found = Place(m.groupValues[1], m.groupValues[2].trim())
            if (found.city.any(Char::isLetter)) return Place(found.postalCode, town.ifEmpty { found.city })
        }
        return Place("", town)
    }
}
