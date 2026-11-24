package com.framepersecond.m3bitwarden.autofill.format

/**
 * Street and house number from identity address lines that do not always keep them together:
 * some entries have "Musterstraße" in the first line and "12a" in the second (or the other way
 * round). Joins them into one line ("Musterstraße 12a") that [AddressSplitter] can split again for
 * forms with separate fields; the remaining lines are the address addition.
 */
object HouseNumberJoiner {
    data class Joined(val line: String, val rest: List<String>)

    private val NUMBER_ONLY = Regex("^(?:(?:nr|no|hausnr)\\.?\\s*)?\\d+\\s?[a-zA-Z]?(?:\\s?[-/]\\s?\\d+\\s?[a-zA-Z]?)?$", RegexOption.IGNORE_CASE)

    fun join(address1: String, address2: String, address3: String): Joined {
        val lines = listOf(address1, address2, address3).map { it.trim() }
        val first = lines[0]
        fun isNumber(s: String) = s.isNotEmpty() && NUMBER_ONLY.matches(s)
        fun number(s: String) = s.replace(Regex("^(?:nr|no|hausnr)\\.?\\s*", RegexOption.IGNORE_CASE), "")
        return when {
            // "Musterstraße" + "12a"
            first.isNotEmpty() && !isNumber(first) && AddressSplitter.split(first).number == null && isNumber(lines[1]) ->
                Joined("$first ${number(lines[1])}", lines.drop(2).filter(String::isNotEmpty))
            // "12a" + "Musterstraße"
            isNumber(first) && lines[1].isNotEmpty() && !isNumber(lines[1]) ->
                Joined("${lines[1]} ${number(first)}", lines.drop(2).filter(String::isNotEmpty))
            else -> Joined(first, lines.drop(1).filter(String::isNotEmpty))
        }
    }
}
