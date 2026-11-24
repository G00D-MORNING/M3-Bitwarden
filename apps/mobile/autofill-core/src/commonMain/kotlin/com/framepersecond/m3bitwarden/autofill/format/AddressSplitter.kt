package com.framepersecond.m3bitwarden.autofill.format

/**
 * Splits one address line into street and house number, for forms that ask for them separately
 * (Otto, Rewe, Magento, Swiss Post, Dutch shops). Bitwarden stores both in "address1".
 *
 * - "Musterstraße 12a", "Hauptstr. 5-7", "Am Ring 3 b", "Leopoldstraße 12/3" (DE/AT/CH/NL style)
 * - "123 Main St", "12 rue de la Paix", "4B Baker Street" (US/UK/FR style)
 */
object AddressSplitter {
    data class Parts(val street: String, val number: String?)

    private val NUMBER = "\\d+\\s?[a-zA-Z]?(?:\\s?[-/]\\s?\\d+\\s?[a-zA-Z]?)?"
    private val NUMBER_LAST = Regex("^(.*?\\D)[\\s,]+($NUMBER)$")
    private val NUMBER_FIRST = Regex("^($NUMBER)[\\s,]+(\\D.*)$")

    fun split(line: String): Parts {
        val text = line.trim().replace(Regex("\\s+"), " ")
        NUMBER_LAST.find(text)?.let { m -> return Parts(m.groupValues[1].trim().trimEnd(','), m.groupValues[2].replace(" ", "")) }
        NUMBER_FIRST.find(text)?.let { m -> return Parts(m.groupValues[2].trim(), m.groupValues[1].replace(" ", "")) }
        return Parts(text, null)
    }
}
