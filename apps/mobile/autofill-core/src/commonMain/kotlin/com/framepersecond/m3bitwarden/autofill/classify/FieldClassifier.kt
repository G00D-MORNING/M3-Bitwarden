package com.framepersecond.m3bitwarden.autofill.classify

import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.model.FieldType
import com.framepersecond.m3bitwarden.autofill.text.TextNorm

/**
 * Scores every field type for one input and keeps the best.
 *
 * Unlike Proton Pass, where the first matching rule wins, every signal adds evidence:
 * Android autofill hints and the HTML `autocomplete` attribute are near-certain,
 * the input type is strong, developer-chosen ids and names are good, placeholders and
 * nearby labels are weaker. Negative evidence (search boxes, coupons, chat fields,
 * multi-line text) subtracts. The result carries the signals for debugging.
 */
class FieldClassifier {

    data class Result(val type: FieldType, val confidence: Int, val signals: List<String>)

    /**
     * @param contextText visible label text found next to the input by [FormAnalyzer].
     */
    fun classify(node: AutofillNode, contextText: List<String> = emptyList()): Result {
        if (!node.isInput) return Result(FieldType.Unknown, 0, emptyList())
        val scores = HashMap<FieldType, Int>()
        val signals = ArrayList<String>()
        fun add(type: FieldType, points: Int, why: String) {
            scores[type] = (scores[type] ?: 0) + points
            signals += "$type+$points:$why"
        }

        val htmlType = node.htmlAttributes["type"]
        if (htmlType != null && htmlType in IGNORED_HTML_TYPES) return Result(FieldType.Unknown, 0, listOf("html type=$htmlType"))

        // 1. Android autofill hints (set by app developers, very reliable).
        for (hint in node.autofillHints) {
            val mapped = ANDROID_HINTS[TextNorm.squash(hint)]
            if (mapped != null) add(mapped, 100, "hint=$hint") else scoreKeywords(hint, 35, "hint-text", ::add)
        }

        // 2. HTML autocomplete tokens (WHATWG spec), equally reliable on the web.
        node.htmlAttributes["autocomplete"]?.split(' ')?.forEach { token ->
            AUTOCOMPLETE[token.trim()]?.let { add(it, 100, "autocomplete=$token") }
        }

        // 3. HTML input type.
        when (htmlType) {
            "password" -> add(FieldType.Password, 70, "type=password")
            "email" -> add(FieldType.Email, 60, "type=email")
            "tel" -> add(FieldType.Phone, 35, "type=tel")
            "number" -> add(FieldType.Otp, 5, "type=number")
        }
        node.htmlAttributes["inputmode"]?.let { if (it == "numeric") add(FieldType.Otp, 10, "inputmode=numeric") }

        // 4. Android input type.
        scoreInputType(node.inputType, ::add)

        // 5. Developer-chosen ids and names, then visible hints, then nearby labels.
        val strong = listOfNotNull(node.idEntry, node.htmlAttributes["name"], node.htmlAttributes["id"])
        val medium = listOfNotNull(
            node.hint,
            node.contentDescription,
            node.htmlAttributes["placeholder"],
            node.htmlAttributes["aria-label"],
            node.htmlAttributes["label"],
            node.htmlAttributes["title"],
            node.htmlAttributes["data-testid"],
        )
        strong.forEach { scoreKeywords(it, 40, "id", ::add) }
        medium.forEach { scoreKeywords(it, 30, "hint", ::add) }
        // The label right next to the input is often the only clue in native apps.
        contextText.forEachIndexed { i, label -> scoreKeywords(label, if (i == 0) 30 else 15, "label", ::add) }

        // 6. Single-digit boxes of a split one-time code.
        if (node.maxLength == 1 && (isNumeric(node) || scores.containsKey(FieldType.Otp))) {
            add(FieldType.OtpDigit, 60, "maxlength=1")
        }
        if (node.maxLength in 4..8 && isNumeric(node)) add(FieldType.Otp, 15, "short numeric")

        // 7. Negative evidence.
        val ownText = (strong + medium).joinToString(" ")
        val denied = matchesAny(ownText, Lexicon.globalDenied)
        if (denied != null) {
            scores.keys.toList().forEach { scores[it] = scores.getValue(it) - 80 }
            signals += "denied:$denied"
        }
        if (isMultiLine(node.inputType)) {
            scores.keys.toList().forEach { scores[it] = scores.getValue(it) - 60 }
            signals += "multiline"
        }

        refinePassword(scores, ownText + " " + contextText.joinToString(" ") + " " + node.htmlAttributes["autocomplete"].orEmpty(), signals)

        val best = scores.entries
            .filter { it.value > 0 }
            .maxWithOrNull(compareBy<Map.Entry<FieldType, Int>> { it.value }.thenBy { -PRIORITY.indexOf(it.key) })
            ?: return Result(FieldType.Unknown, 0, signals)
        val confidence = best.value.coerceAtMost(100)
        return if (confidence >= MIN_CONFIDENCE) Result(best.key, confidence, signals) else Result(FieldType.Unknown, confidence, signals)
    }

    private fun scoreKeywords(raw: String, weight: Int, source: String, add: (FieldType, Int, String) -> Unit) {
        if (raw.isBlank()) return
        val squashed = TextNorm.squash(raw)
        val words = TextNorm.words(raw).toSet()
        if (containsKeyword(squashed, words, Lexicon.password)) add(FieldType.Password, weight, "$source:password")
        for (entry in Lexicon.entries) {
            val hit = entry.keywords.firstOrNull { matchesKeyword(squashed, words, it) } ?: continue
            if (entry.denied.any { matchesKeyword(squashed, words, it) }) continue
            add(entry.type, weight, "$source:$hit")
        }
    }

    private fun refinePassword(scores: MutableMap<FieldType, Int>, text: String, signals: MutableList<String>) {
        val pw = scores[FieldType.Password] ?: return
        if (pw <= 0) return
        val squashed = TextNorm.squash(text)
        val words = TextNorm.words(text).toSet()
        when {
            text.contains("new-password") || text.contains("current-password") -> Unit // autocomplete already decided
            containsKeyword(squashed, words, Lexicon.confirmPasswordMarkers) -> {
                scores[FieldType.ConfirmPassword] = pw + 10; scores.remove(FieldType.Password); signals += "confirm-marker"
            }
            containsKeyword(squashed, words, Lexicon.currentPasswordMarkers) -> signals += "current-marker"
            containsKeyword(squashed, words, Lexicon.newPasswordMarkers) -> {
                scores[FieldType.NewPassword] = pw + 10; scores.remove(FieldType.Password); signals += "new-marker"
            }
        }
        // A field announced as new-password must never be offered an old one.
        scores[FieldType.NewPassword]?.let { if (it >= 100) scores.remove(FieldType.Password) }
    }

    private fun scoreInputType(inputType: Int, add: (FieldType, Int, String) -> Unit) {
        if (inputType == 0) return
        val cls = inputType and InputTypeBits.MASK_CLASS
        val variation = inputType and InputTypeBits.MASK_VARIATION
        when (cls) {
            InputTypeBits.CLASS_TEXT -> when (variation) {
                InputTypeBits.TEXT_VARIATION_PASSWORD, InputTypeBits.TEXT_VARIATION_WEB_PASSWORD -> add(FieldType.Password, 70, "inputType=password")
                InputTypeBits.TEXT_VARIATION_VISIBLE_PASSWORD -> add(FieldType.Password, 40, "inputType=visiblePassword")
                InputTypeBits.TEXT_VARIATION_EMAIL_ADDRESS, InputTypeBits.TEXT_VARIATION_WEB_EMAIL_ADDRESS -> add(FieldType.Email, 60, "inputType=email")
                InputTypeBits.TEXT_VARIATION_PERSON_NAME -> add(FieldType.FullName, 25, "inputType=personName")
                InputTypeBits.TEXT_VARIATION_POSTAL_ADDRESS -> add(FieldType.Address, 25, "inputType=postalAddress")
            }
            InputTypeBits.CLASS_NUMBER -> if (variation == InputTypeBits.NUMBER_VARIATION_PASSWORD) {
                add(FieldType.Password, 35, "inputType=numberPassword")
                add(FieldType.Otp, 20, "inputType=numberPassword")
            } else {
                add(FieldType.Otp, 5, "inputType=number")
            }
            InputTypeBits.CLASS_PHONE -> add(FieldType.Phone, 30, "inputType=phone")
        }
    }

    private fun isNumeric(node: AutofillNode): Boolean =
        (node.inputType and InputTypeBits.MASK_CLASS) == InputTypeBits.CLASS_NUMBER ||
            node.htmlAttributes["inputmode"] == "numeric" ||
            node.htmlAttributes["type"] == "number" ||
            node.htmlAttributes["pattern"]?.contains("[0-9]") == true ||
            node.htmlAttributes["pattern"]?.contains("\\d") == true

    private fun isMultiLine(inputType: Int): Boolean =
        inputType and (InputTypeBits.TEXT_FLAG_MULTI_LINE or InputTypeBits.TEXT_FLAG_IME_MULTI_LINE) != 0

    companion object {
        const val MIN_CONFIDENCE = 30

        /** Tie-breaker when two types score the same: earlier wins. */
        private val PRIORITY = listOf(
            FieldType.NewPassword, FieldType.ConfirmPassword, FieldType.Password, FieldType.OtpDigit, FieldType.Otp,
            FieldType.Email, FieldType.Username, FieldType.CardNumber, FieldType.CardCvv, FieldType.CardExpiry,
            FieldType.CardExpiryMonth, FieldType.CardExpiryYear, FieldType.CardHolder, FieldType.FirstName,
            FieldType.LastName, FieldType.FullName, FieldType.Phone, FieldType.PostalCode, FieldType.City,
            FieldType.Country, FieldType.Address, FieldType.Organization,
        )

        private val IGNORED_HTML_TYPES = setOf("hidden", "checkbox", "radio", "submit", "button", "reset", "file", "image", "range", "color", "search", "date", "datetime-local", "time")

        /** Android View.AUTOFILL_HINT_* and androidx HintConstants, squashed. */
        private val ANDROID_HINTS: Map<String, FieldType> = mapOf(
            "username" to FieldType.Username,
            "newusername" to FieldType.Username,
            "emailaddress" to FieldType.Email,
            "password" to FieldType.Password,
            "currentpassword" to FieldType.Password,
            "newpassword" to FieldType.NewPassword,
            "smsotpcode" to FieldType.Otp,
            "2faappotpcode" to FieldType.Otp,
            "otp" to FieldType.Otp,
            "creditcardnumber" to FieldType.CardNumber,
            "creditcardsecuritycode" to FieldType.CardCvv,
            "creditcardexpirationdate" to FieldType.CardExpiry,
            "creditcardexpirationmonth" to FieldType.CardExpiryMonth,
            "creditcardexpirationyear" to FieldType.CardExpiryYear,
            "personname" to FieldType.FullName,
            "persongivenname" to FieldType.FirstName,
            "personfamilyname" to FieldType.LastName,
            "phone" to FieldType.Phone,
            "phonenumber" to FieldType.Phone,
            "postaladdress" to FieldType.Address,
            "postalcode" to FieldType.PostalCode,
            "addresslocality" to FieldType.City,
            "addresscountry" to FieldType.Country,
        )

        private val AUTOCOMPLETE: Map<String, FieldType> = mapOf(
            "username" to FieldType.Username,
            "email" to FieldType.Email,
            "current-password" to FieldType.Password,
            "new-password" to FieldType.NewPassword,
            "one-time-code" to FieldType.Otp,
            "cc-number" to FieldType.CardNumber,
            "cc-name" to FieldType.CardHolder,
            "cc-exp" to FieldType.CardExpiry,
            "cc-exp-month" to FieldType.CardExpiryMonth,
            "cc-exp-year" to FieldType.CardExpiryYear,
            "cc-csc" to FieldType.CardCvv,
            "name" to FieldType.FullName,
            "given-name" to FieldType.FirstName,
            "family-name" to FieldType.LastName,
            "tel" to FieldType.Phone,
            "tel-national" to FieldType.Phone,
            "street-address" to FieldType.Address,
            "address-line1" to FieldType.Address,
            "postal-code" to FieldType.PostalCode,
            "address-level2" to FieldType.City,
            "country" to FieldType.Country,
            "country-name" to FieldType.Country,
            "organization" to FieldType.Organization,
        )

        /** Short keywords (≤ 4 chars) must be a whole word; longer ones may be a substring. */
        internal fun matchesKeyword(squashed: String, words: Set<String>, keyword: String): Boolean =
            if (keyword.length <= 4) keyword in words || squashed == keyword else squashed.contains(keyword)

        internal fun containsKeyword(squashed: String, words: Set<String>, keywords: Set<String>): Boolean =
            keywords.any { matchesKeyword(squashed, words, it) }

        internal fun matchesAny(raw: String, keywords: Set<String>): String? {
            val squashed = TextNorm.squash(raw)
            val words = TextNorm.words(raw).toSet()
            return keywords.firstOrNull { matchesKeyword(squashed, words, it) }
        }
    }
}

/** Subset of `android.text.InputType`, copied so this module stays platform-neutral. */
object InputTypeBits {
    const val MASK_CLASS = 0x0000000f
    const val MASK_VARIATION = 0x00000ff0
    const val CLASS_TEXT = 0x00000001
    const val CLASS_NUMBER = 0x00000002
    const val CLASS_PHONE = 0x00000003
    const val TEXT_FLAG_MULTI_LINE = 0x00020000
    const val TEXT_FLAG_IME_MULTI_LINE = 0x00040000
    const val TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    const val TEXT_VARIATION_PERSON_NAME = 0x00000060
    const val TEXT_VARIATION_POSTAL_ADDRESS = 0x00000070
    const val TEXT_VARIATION_PASSWORD = 0x00000080
    const val TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    const val TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    const val NUMBER_VARIATION_PASSWORD = 0x00000010
}
