package com.framepersecond.m3bitwarden.autofill.format

import com.framepersecond.m3bitwarden.autofill.text.TextNorm

/**
 * Country names in the languages shops use, plus ISO codes, so "Deutschland" in the vault matches
 * an option "Germany", "DE" or "DEU" (and the other way round).
 */
object Countries {
    private val table: Map<String, List<String>> = mapOf(
        "DE" to listOf("DEU", "Deutschland", "Germany", "Allemagne", "Alemania", "Germania", "Duitsland", "Niemcy", "Bundesrepublik Deutschland"),
        "AT" to listOf("AUT", "Österreich", "Austria", "Autriche", "Oostenrijk"),
        "CH" to listOf("CHE", "Schweiz", "Switzerland", "Suisse", "Svizzera", "Zwitserland"),
        "LI" to listOf("LIE", "Liechtenstein"),
        "NL" to listOf("NLD", "Niederlande", "Netherlands", "Nederland", "Pays-Bas", "Holland"),
        "BE" to listOf("BEL", "Belgien", "Belgium", "Belgique", "België"),
        "LU" to listOf("LUX", "Luxemburg", "Luxembourg"),
        "FR" to listOf("FRA", "Frankreich", "France", "Francia", "Frankrijk"),
        "IT" to listOf("ITA", "Italien", "Italy", "Italia", "Italie"),
        "ES" to listOf("ESP", "Spanien", "Spain", "España", "Espagne"),
        "PT" to listOf("PRT", "Portugal"),
        "PL" to listOf("POL", "Polen", "Poland", "Polska", "Pologne"),
        "CZ" to listOf("CZE", "Tschechien", "Czechia", "Czech Republic", "Česko", "Tschechische Republik"),
        "SK" to listOf("SVK", "Slowakei", "Slovakia", "Slovensko"),
        "HU" to listOf("HUN", "Ungarn", "Hungary", "Magyarország"),
        "SI" to listOf("SVN", "Slowenien", "Slovenia"),
        "HR" to listOf("HRV", "Kroatien", "Croatia", "Hrvatska"),
        "DK" to listOf("DNK", "Dänemark", "Denmark", "Danmark"),
        "SE" to listOf("SWE", "Schweden", "Sweden", "Sverige"),
        "NO" to listOf("NOR", "Norwegen", "Norway", "Norge"),
        "FI" to listOf("FIN", "Finnland", "Finland", "Suomi"),
        "IE" to listOf("IRL", "Irland", "Ireland"),
        "GB" to listOf("GBR", "UK", "Vereinigtes Königreich", "United Kingdom", "Großbritannien", "Great Britain", "England"),
        "US" to listOf("USA", "Vereinigte Staaten", "United States", "United States of America", "Vereinigte Staaten von Amerika"),
        "CA" to listOf("CAN", "Kanada", "Canada"),
        "GR" to listOf("GRC", "Griechenland", "Greece"),
        "TR" to listOf("TUR", "Türkei", "Turkey", "Türkiye"),
        "RO" to listOf("ROU", "Rumänien", "Romania"),
        "BG" to listOf("BGR", "Bulgarien", "Bulgaria"),
    )

    private val byName: Map<String, String> = buildMap {
        for ((code, names) in table) {
            put(TextNorm.squash(code), code)
            names.forEach { put(TextNorm.squash(it), code) }
        }
    }

    /** ISO 3166-1 alpha-2 code for a name or code in any of the listed languages. */
    fun code(text: String?): String? = byName[TextNorm.squash(text)]
}
