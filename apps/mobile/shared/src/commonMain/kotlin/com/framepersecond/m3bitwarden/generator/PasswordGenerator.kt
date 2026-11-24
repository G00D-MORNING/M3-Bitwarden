package com.framepersecond.m3bitwarden.generator

import androidx.compose.runtime.Immutable
import com.framepersecond.m3bitwarden.platform.secureRandomInt
import kotlin.math.log2
import kotlin.math.roundToInt

@Immutable
data class PasswordOptions(
    val length: Int = 20,
    val uppercase: Boolean = true,
    val lowercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val avoidAmbiguous: Boolean = false,
)

@Immutable
data class PassphraseOptions(
    val words: Int = 5,
    val separator: String = "-",
    val capitalize: Boolean = true,
    val includeNumber: Boolean = true,
)

enum class Strength { Weak, Fair, Strong, VeryStrong }

object PasswordGenerator {
    const val MIN_LENGTH = 5
    const val MAX_LENGTH = 64
    const val MIN_WORDS = 3
    const val MAX_WORDS = 12

    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*"
    private const val AMBIGUOUS = "IlO01"

    private fun sets(options: PasswordOptions): List<String> = buildList {
        if (options.uppercase) add(UPPER)
        if (options.lowercase) add(LOWER)
        if (options.digits) add(DIGITS)
        if (options.symbols) add(SYMBOLS)
    }.map { set -> if (options.avoidAmbiguous) set.filterNot { it in AMBIGUOUS } else set }
        .ifEmpty { listOf(LOWER) }

    /** Random password that contains at least one character of every enabled set. */
    fun password(options: PasswordOptions): String {
        val sets = sets(options)
        val pool = sets.joinToString("")
        val length = options.length.coerceIn(maxOf(MIN_LENGTH, sets.size), MAX_LENGTH)
        val chars = CharArray(length)
        sets.forEachIndexed { index, set -> chars[index] = set[secureRandomInt(set.length)] }
        for (i in sets.size until length) chars[i] = pool[secureRandomInt(pool.length)]
        // Fisher–Yates so the guaranteed characters do not sit at the front.
        for (i in chars.lastIndex downTo 1) {
            val j = secureRandomInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return chars.concatToString()
    }

    fun passphrase(options: PassphraseOptions): String {
        val count = options.words.coerceIn(MIN_WORDS, MAX_WORDS)
        val words = MutableList(count) {
            val word = WordList.words[secureRandomInt(WordList.words.size)]
            if (options.capitalize) word.replaceFirstChar { it.uppercaseChar() } else word
        }
        if (options.includeNumber) {
            val index = secureRandomInt(count)
            words[index] = words[index] + secureRandomInt(10)
        }
        return words.joinToString(options.separator)
    }

    fun entropyBits(options: PasswordOptions): Double =
        options.length * log2(sets(options).sumOf { it.length }.toDouble())

    fun entropyBits(options: PassphraseOptions): Double =
        options.words * log2(WordList.words.size.toDouble()) + if (options.includeNumber) log2(10.0 * options.words) else 0.0

    /** Rough strength of an arbitrary existing password, based on its character classes. */
    fun estimateBits(password: String): Double {
        if (password.isEmpty()) return 0.0
        var pool = 0
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 32
        var bits = password.length * log2(pool.coerceAtLeast(1).toDouble())
        // A trailing year ("Sommer2019") is the first thing cracking tools try.
        if (Regex("(19|20)\\d\\d$").containsMatchIn(password)) bits -= 14
        return bits.coerceAtLeast(0.0)
    }

    fun strength(bits: Double): Strength = when {
        bits < 50 -> Strength.Weak
        bits < 75 -> Strength.Fair
        bits < 110 -> Strength.Strong
        else -> Strength.VeryStrong
    }

    /** 0..1 for progress indicators; 128 bits and above count as full. */
    fun progress(bits: Double): Float = (bits / 128.0).coerceIn(0.04, 1.0).toFloat()

    fun formatBits(bits: Double): String = "${bits.roundToInt()} Bit"
}
