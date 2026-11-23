package com.framepersecond.m3bitwarden.data

/**
 * Placeholder for verification codes in the UI prototype. This is NOT RFC 6238 and must not be
 * used for real accounts: real codes come from the Bitwarden SDK, which already implements TOTP.
 */
object DemoTotp {
    const val PERIOD_SECONDS = 30

    fun code(secret: String, epochSeconds: Long): String {
        val window = epochSeconds / PERIOD_SECONDS
        var hash = 1125899906842597L
        (secret + window).forEach { hash = 31 * hash + it.code }
        val value = ((hash xor (hash ushr 29)) and 0x7fffffff) % 1_000_000
        return value.toString().padStart(6, '0')
    }

    fun secondsLeft(epochSeconds: Long): Int = PERIOD_SECONDS - (epochSeconds % PERIOD_SECONDS).toInt()
}
