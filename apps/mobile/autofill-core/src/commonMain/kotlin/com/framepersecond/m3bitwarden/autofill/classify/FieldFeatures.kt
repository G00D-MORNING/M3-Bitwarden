package com.framepersecond.m3bitwarden.autofill.classify

import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.text.TextNorm

/**
 * Turns one input into sparse features for [LearnedModel]: words and character n-grams of the
 * developer-chosen ids, of the visible hints and of the nearby labels (kept apart, because an id
 * says more than a label), plus input type, length and list shape. Never uses typed values.
 */
object FieldFeatures {
    private const val MAX_NGRAMS_PER_TEXT = 40

    fun extract(node: AutofillNode, labels: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        val ids = listOfNotNull(node.idEntry, node.htmlAttributes["name"], node.htmlAttributes["id"])
        val hints = listOfNotNull(
            node.hint, node.contentDescription, node.htmlAttributes["placeholder"], node.htmlAttributes["aria-label"],
            node.htmlAttributes["title"], node.htmlAttributes["data-fieldtype"],
        )
        val labelTexts = listOfNotNull(node.htmlAttributes["label"]) + labels.take(1)
        text(ids, "i", out)
        text(hints, "h", out)
        text(labelTexts, "l", out)

        node.htmlAttributes["type"]?.let { out += "t:$it" }
        node.htmlAttributes["inputmode"]?.let { out += "im:$it" }
        node.htmlAttributes["autocomplete"]?.split(' ')?.filter { it.isNotBlank() }?.forEach { out += "ac:$it" }
        if (node.inputType != 0) {
            out += "ic:${node.inputType and InputTypeBits.MASK_CLASS}"
            out += "iv:${node.inputType and InputTypeBits.MASK_VARIATION}"
        }
        out += "len:" + when (node.maxLength) {
            in Int.MIN_VALUE..0 -> "none"
            1 -> "1"
            2 -> "2"
            in 3..4 -> "3-4"
            in 5..8 -> "5-8"
            in 9..20 -> "9-20"
            else -> "long"
        }
        if (node.isSelect) {
            out += "select"
            val opts = node.options.map(TextNorm::squash).filter { it.isNotEmpty() }
            val nums = opts.mapNotNull { it.toIntOrNull() }
            if (nums.size >= 5 && nums.all { it in 1..12 }) out += "opt:months"
            if (nums.size >= 5 && nums.all { it in 2000..2100 }) out += "opt:years"
            opts.take(12).forEach { out += "o:$it" }
        }
        if (ids.isEmpty() && hints.isEmpty() && labelTexts.isEmpty()) out += "bare"
        out += "bias"
        return out.toList()
    }

    private fun text(parts: List<String>, prefix: String, out: MutableSet<String>) {
        for (raw in parts) {
            if (raw.isBlank()) continue
            TextNorm.words(raw).filter { it.length >= 2 && !it.all(Char::isDigit) }.forEach { out += "$prefix:$it" }
            // Character 4-grams carry over to unseen compounds: "kreditkartennr" ~ "kartennummer".
            val squashed = TextNorm.squash(raw).filterNot(Char::isDigit)
            var added = 0
            for (i in 0..squashed.length - 4) {
                out += "${prefix}4:${squashed.substring(i, i + 4)}"
                if (++added >= MAX_NGRAMS_PER_TEXT) break
            }
        }
    }
}
