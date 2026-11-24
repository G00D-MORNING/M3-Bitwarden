package com.framepersecond.m3bitwarden.android.vault

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Vaultwarden answers in camelCase or PascalCase depending on endpoint and version, so every
// lookup ignores the case of the first letter (like the iOS decoder).

fun JSONObject.anyOf(key: String): Any? {
    if (has(key)) return get(key).takeUnless { it == JSONObject.NULL }
    val alt = key.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it.lowercaseChar() }
    if (has(alt)) return get(alt).takeUnless { it == JSONObject.NULL }
    val k = keys().asSequence().firstOrNull { it.equals(key, ignoreCase = true) } ?: return null
    return get(k).takeUnless { it == JSONObject.NULL }
}

fun JSONObject.str(key: String): String? = anyOf(key)?.toString()
fun JSONObject.int(key: String): Int? = when (val v = anyOf(key)) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}
fun JSONObject.bool(key: String): Boolean? = when (val v = anyOf(key)) {
    is Boolean -> v
    is String -> v.toBooleanStrictOrNull()
    else -> null
}
fun JSONObject.obj(key: String): JSONObject? = anyOf(key) as? JSONObject
fun JSONObject.arr(key: String): JSONArray? = anyOf(key) as? JSONArray
fun JSONObject.date(key: String): Instant? = str(key)?.let(::parseDate)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** ISO-8601 with or without zone and with up to 7 fraction digits (.NET style). */
fun parseDate(text: String): Instant? = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
    ?: runCatching { Instant.parse(text) }.getOrNull()
    ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()

fun Instant.iso(): String = toString()

/** JSONObject.put that skips nulls, so absent values stay absent. */
fun JSONObject.putOpt(key: String, value: Any?): JSONObject = apply { if (value != null) put(key, value) }
