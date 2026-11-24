package com.framepersecond.m3bitwarden.autofill.format

import com.framepersecond.m3bitwarden.autofill.ValueKind
import com.framepersecond.m3bitwarden.autofill.model.FieldFormat
import com.framepersecond.m3bitwarden.autofill.text.TextNorm

/** What goes into one field: text, or the index of an option in a list. */
sealed interface FillValue {
    data class Text(val text: String) : FillValue
    data class ListIndex(val index: Int) : FillValue
}

/**
 * Shapes a vault value for one field, using what the field shows about itself: its placeholder
 * ("MM/JJ", "MM / YYYY"), its length limit and, for lists, its options.
 *
 * Raw values come in a fixed form from the vault layer: card expiry as "MM/YYYY", month as a
 * number, year with four digits when known, street and house number together as "address1".
 * Returns null when a list has no fitting option; a wrong selection is worse than none.
 */
object ValueFormatter {

    fun format(kind: ValueKind, raw: String?, format: FieldFormat): FillValue? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (format.isSelect && format.options.isNotEmpty()) {
            return pickOption(kind, value, format.options)?.let { FillValue.ListIndex(it) }
        }
        val text = when (kind) {
            ValueKind.CardExpiry -> expiry(value, format)
            ValueKind.CardExpiryMonth -> month(value)?.let { m -> if (format.maxLength == 1 || (format.placeholder?.trim()?.lowercase() == "m")) "$m" else pad(m) } ?: value
            ValueKind.CardExpiryYear -> year(value)?.let { y -> if (twoDigitYear(format)) pad(y % 100) else "$y" } ?: value
            ValueKind.CardNumber, ValueKind.CardCvv -> value.filter { it.isDigit() }.ifEmpty { value }
            ValueKind.Street -> AddressSplitter.split(value).street
            ValueKind.HouseNumber -> AddressSplitter.split(value).number ?: return null
            ValueKind.Country -> if (format.maxLength in 2..3) (Countries.code(value) ?: value).take(format.maxLength) else value
            ValueKind.Phone -> if (format.maxLength in 1..15) value.filter { it.isDigit() || it == '+' } else value
            ValueKind.PhoneCountryCode -> PhoneSplitter.split(value).countryCode?.let { cc ->
                if (format.maxLength in 1..3 || format.placeholder?.startsWith("+") == false) cc else "+$cc"
            } ?: return null
            ValueKind.PhoneAreaCode -> PhoneSplitter.split(value).areaCode ?: return null
            ValueKind.PhoneLocal -> {
                val local = PhoneSplitter.split(value).local
                // US layout 3 | 4: the 3-digit box takes the start, the 4-digit box the end.
                when (format.maxLength) {
                    3 -> local.take(3)
                    4 -> local.takeLast(4)
                    else -> local
                }
            }
            else -> value
        }
        return FillValue.Text(if (format.maxLength > 0 && text.length > format.maxLength) text.take(format.maxLength) else text)
    }

    // ---------------------------------------------------------------- card expiry

    private fun expiry(value: String, format: FieldFormat): String {
        val (m, y) = parseExpiry(value) ?: return value
        val ph = format.placeholder?.lowercase()?.trim().orEmpty()
        val fourDigits = when {
            ph.contains("yyyy") || ph.contains("jjjj") || ph.contains("aaaa") -> true
            ph.contains("yy") || ph.contains("jj") || ph.contains("aa") -> false
            format.maxLength == 6 || format.maxLength == 7 || format.maxLength in 8..9 -> true
            else -> false
        }
        val separator = when {
            ph.contains(" / ") -> " / "
            ph.contains("/") -> "/"
            ph.contains("-") -> "-"
            ph.contains(".") -> "."
            ph.isNotEmpty() && ph.none { !it.isLetter() } -> ""
            format.maxLength == 4 || format.maxLength == 6 -> ""
            else -> "/"
        }
        val yearText = if (fourDigits) "$y" else pad(y % 100)
        return pad(m) + separator + yearText
    }

    private fun parseExpiry(value: String): Pair<Int, Int>? {
        val parts = value.split('/', '-', '.', ' ').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val m = month(parts[0]) ?: return null
        val y = year(parts.last()) ?: return null
        return m to y
    }

    private fun month(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 1..12 }

    private fun year(value: String): Int? {
        val n = value.trim().toIntOrNull() ?: return null
        return when (n) {
            in 0..99 -> 2000 + n
            in 2000..2199 -> n
            else -> null
        }
    }

    private fun twoDigitYear(format: FieldFormat): Boolean {
        val ph = format.placeholder?.lowercase()?.trim().orEmpty()
        return format.maxLength == 2 || ph == "yy" || ph == "jj" || ph == "aa"
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    // ---------------------------------------------------------------- lists

    private val MONTH_NAMES = listOf(
        listOf("jan", "januar", "january", "janvier", "enero", "gennaio"),
        listOf("feb", "februar", "february", "fevrier", "febrero", "febbraio"),
        listOf("mar", "maerz", "march", "mars", "marzo"),
        listOf("apr", "april", "avril", "abril", "aprile"),
        listOf("mai", "may", "mayo", "maggio"),
        listOf("jun", "juni", "june", "juin", "junio", "giugno"),
        listOf("jul", "juli", "july", "juillet", "julio", "luglio"),
        listOf("aug", "august", "aout", "agosto"),
        listOf("sep", "sept", "september", "septembre", "septiembre", "settembre"),
        listOf("okt", "oct", "oktober", "october", "octobre", "octubre", "ottobre"),
        listOf("nov", "november", "novembre", "noviembre"),
        listOf("dez", "dec", "dezember", "december", "decembre", "diciembre", "dicembre"),
    )

    private enum class Salutation { Male, Female, Diverse, Doctor }

    private fun salutation(text: String): Salutation? {
        val t = TextNorm.squash(text)
        return when {
            t.startsWith("dr") || t.startsWith("prof") -> Salutation.Doctor
            t in setOf("herr", "herrn", "mr", "m", "monsieur", "senor", "sr", "signor", "dhr", "male", "maennlich", "pan") -> Salutation.Male
            t in setOf("frau", "mrs", "ms", "miss", "mme", "madame", "mlle", "senora", "sra", "signora", "mevr", "female", "weiblich", "pani") -> Salutation.Female
            t in setOf("divers", "mx", "diverse", "other", "keineangabe", "ohneanrede") -> Salutation.Diverse
            else -> null
        }
    }

    internal fun pickOption(kind: ValueKind, value: String, options: List<String>): Int? {
        val squashed = options.map { TextNorm.squash(it) }
        fun firstIndex(predicate: (Int) -> Boolean): Int? = squashed.indices.firstOrNull(predicate)
        return when (kind) {
            ValueKind.CardExpiryMonth, ValueKind.CardExpiry -> {
                val m = month(value) ?: parseExpiry(value)?.first ?: return null
                firstIndex { i ->
                    val digits = squashed[i].takeWhile { it.isDigit() }
                    digits.toIntOrNull() == m || MONTH_NAMES[m - 1].any { squashed[i].startsWith(it) && squashed[i].none(Char::isDigit) }
                }
            }
            ValueKind.CardExpiryYear -> {
                val y = year(value) ?: return null
                firstIndex { i -> squashed[i].toIntOrNull()?.let { it == y || it == y % 100 } == true }
            }
            ValueKind.Country -> {
                val code = Countries.code(value)
                code?.let { c -> firstIndex { i -> Countries.code(options[i]) == c } } ?: textMatch(TextNorm.squash(value), squashed)
            }
            ValueKind.CardBrand -> {
                val want = brandKey(value)
                firstIndex { i -> brandKey(options[i]) == want && want != null } ?: textMatch(TextNorm.squash(value), squashed)
            }
            ValueKind.PhoneCountryCode -> {
                val cc = PhoneSplitter.split(value).countryCode ?: return null
                firstIndex { i -> Regex("(^|\\D)\\+?$cc(\\D|$)").containsMatchIn(options[i]) }
            }
            ValueKind.Title -> {
                val s = salutation(value)
                s?.let { want -> firstIndex { i -> salutation(options[i]) == want } } ?: textMatch(TextNorm.squash(value), squashed)
            }
            else -> textMatch(TextNorm.squash(value), squashed)
        }
    }

    private fun brandKey(text: String): String? {
        val t = TextNorm.squash(text)
        return when {
            "american" in t || t.startsWith("amex") -> "amex"
            "master" in t || t == "mc" -> "mastercard"
            "visa" in t -> "visa"
            "maestro" in t -> "maestro"
            "diners" in t -> "diners"
            "discover" in t -> "discover"
            "jcb" in t -> "jcb"
            "unionpay" in t -> "unionpay"
            else -> null
        }
    }

    /** Exact, then prefix, then containment, ignoring case, accents and separators. */
    private fun textMatch(value: String, options: List<String>): Int? {
        if (value.isEmpty()) return null
        options.indexOf(value).takeIf { it >= 0 }?.let { return it }
        options.indexOfFirst { it.isNotEmpty() && (it.startsWith(value) || value.startsWith(it)) && minOf(it.length, value.length) >= 3 }.takeIf { it >= 0 }?.let { return it }
        return options.indexOfFirst { it.length >= 4 && (it.contains(value) || value.contains(it)) }.takeIf { it >= 0 }
    }
}
