package com.framepersecond.m3bitwarden.android.vault

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Minimal ASN.1 DER writer, enough to build a PKCS#10 certificate signing request. */
object Der {
    fun sequence(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    fun integer(value: Int) = tlv(0x02, byteArrayOf(value.toByte()))
    fun utf8(text: String) = tlv(0x0C, text.toByteArray(Charsets.UTF_8))
    fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)
    fun contextConstructed(tag: Int, content: ByteArray) = tlv(0xA0 or tag, content)

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val chunk = ArrayDeque<Int>()
            var rest = arc
            chunk.addFirst((rest and 0x7F).toInt())
            rest = rest shr 7
            while (rest > 0) {
                chunk.addFirst(((rest and 0x7F) or 0x80).toInt())
                rest = rest shr 7
            }
            chunk.forEach(out::write)
        }
        return tlv(0x06, out.toByteArray())
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        if (n < 0x80) {
            out.write(n)
        } else {
            val len = ArrayDeque<Int>()
            var rest = n
            while (rest > 0) {
                len.addFirst(rest and 0xFF)
                rest = rest shr 8
            }
            out.write(0x80 or len.size)
            len.forEach(out::write)
        }
        out.write(content)
        return out.toByteArray()
    }

    fun pem(der: ByteArray, label: String): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$b64\n-----END $label-----\n"
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }
}
