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
class FieldClassifier(
    /** Learned fallback for fields the rules cannot place; null disables it. */
    private val model: LearnedModel? = LearnedModel.shipped,
    /** Minimum lead of the model's best class over the runner-up; null = the margin chosen in training. */
    private val modelMargin: Int? = null,
    /** Let a very confident model overrule a weak rule decision (label words only); null = as trained. */
    private val modelOverrides: Boolean? = null,
) {

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
        // Read-only and disabled fields show a value the page set itself (FRITZ!Box user list,
        // Proxmox realm picker); filling them is pointless and can break the form.
        if (node.htmlAttributes.containsKey("readonly") || node.htmlAttributes.containsKey("disabled")) {
            return Result(FieldType.Unknown, 0, listOf("readonly"))
        }

        // 1. Android autofill hints (set by app developers, very reliable).
        for (hint in node.autofillHints) {
            val mapped = ANDROID_HINTS[TextNorm.squash(hint)]
            if (mapped != null) add(mapped, 100, "hint=$hint") else scoreKeywords(hint, 35, "hint-text", ::add)
        }

        // 2. HTML autocomplete (WHATWG): modifiers, then one field name. An attribute with
        // unknown tokens is invalid as a whole and ignored, as browsers do.
        when (val ac = parseAutocomplete(node.htmlAttributes["autocomplete"])) {
            null -> Unit
            in AUTOCOMPLETE_NOT_FILLED -> return Result(FieldType.Unknown, 0, listOf("autocomplete=$ac"))
            // "new-password" on a text field is a widespread trick to keep Chrome from filling it.
            "new-password", "current-password" -> if (htmlType == null || htmlType == "password") AUTOCOMPLETE[ac]?.let { add(it, 100, "autocomplete=$ac") }
            else -> AUTOCOMPLETE[ac]?.let { add(it, 100, "autocomplete=$ac") }
        }

        // 2b. Chrome's own field-type prediction (crowd-sourced and local heuristics), reported
        // to autofill services in the attributes below. Strong, but autocomplete still wins.
        for (attr in CHROME_PREDICTION_ATTRS) {
            node.htmlAttributes[attr]?.split(',', ' ')?.forEach { token ->
                CHROME_TYPES[token.trim().uppercase()]?.let { add(it, 80, "$attr=$token") }
            }
        }

        // 2c. Option texts of a select: "Herr/Frau", country names, months, years.
        if (node.isSelect) scoreOptions(node.options, ::add)

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
        medium.forEach { text ->
            // "you@company.com" shows the expected format; its words ("company") are no hint.
            if (EXAMPLE_EMAIL.matches(text.trim())) add(FieldType.Email, 50, "example-email") else scoreKeywords(text, 30, "hint", ::add)
        }
        // The label right next to the input is often the only clue in native apps.
        contextText.forEachIndexed { i, label -> scoreKeywords(label, if (i == 0) 30 else 15, "label", ::add) }

        // 6. Single-digit boxes of a split one-time code.
        if (node.maxLength == 1 && (isNumeric(node) || scores.containsKey(FieldType.Otp))) {
            add(FieldType.OtpDigit, 60, "maxlength=1")
        }
        if (node.maxLength in 4..8 && isNumeric(node)) add(FieldType.Otp, 15, "short numeric")

        // 7. Negative evidence. A search box, coupon or IBAN field stays empty unless the page
        // itself declares the field (autocomplete / autofill hints).
        val ownText = (strong + medium).joinToString(" ")
        val denied = matchesAny(ownText, Lexicon.globalDenied)
        val declared = signals.any { it.contains("+100:") }
        if (denied != null && !declared) {
            return Result(FieldType.Unknown, 0, signals + "denied:$denied")
        }
        if (denied != null) signals += "denied-but-declared:$denied"

        applySpecificity(scores, signals, htmlType, declared)
        if (isMultiLine(node.inputType)) {
            scores.keys.toList().forEach { scores[it] = scores.getValue(it) - 60 }
            signals += "multiline"
        }

        refinePassword(scores, ownText + " " + contextText.joinToString(" ") + " " + node.htmlAttributes["autocomplete"].orEmpty(), signals)

        val best = scores.entries
            .filter { it.value > 0 }
            .maxWithOrNull(compareBy<Map.Entry<FieldType, Int>> { it.value }.thenBy { -PRIORITY.indexOf(it.key) })
        if (best == null || best.value < MIN_CONFIDENCE) {
            // No rule fired (unknown wording, foreign language, obfuscated ids): ask the model.
            return modelFallback(node, contextText, signals) ?: Result(FieldType.Unknown, best?.value ?: 0, signals)
        }
        // Weak rule decisions (only label or placeholder words, no ids, types or declarations)
        // give way to a model that is far more certain.
        val overrides = modelOverrides ?: (model?.overridesWeakRules == true)
        if (overrides && best.value < WEAK_RULE && model != null && !isMultiLine(node.inputType)) {
            val (type, margin) = model.predict(FieldFeatures.extract(node, contextText))
            if (type != best.key && type != FieldType.Unknown && margin >= 2 * (modelMargin ?: model.margin)) {
                return Result(type, MIN_CONFIDENCE + 10, signals + "model-over-weak-rule:$type/$margin")
            }
        }

        // A field typed as e-mail that the page calls "username" takes an e-mail address.
        if (best.key == FieldType.Username && htmlType == "email") return Result(FieldType.Email, best.value.coerceAtMost(100), signals + "type=email-wins")
        val confidence = best.value.coerceAtMost(100)
        return if (confidence >= MIN_CONFIDENCE) Result(best.key, confidence, signals) else Result(FieldType.Unknown, confidence, signals)
    }

    private fun scoreKeywords(raw: String, weight: Int, source: String, add: (FieldType, Int, String) -> Unit) {
        if (raw.isBlank()) return
        val squashed = TextNorm.squash(raw)
        val words = TextNorm.words(raw).toSet()
        if (containsKeyword(squashed, words, Lexicon.password)) add(FieldType.Password, weight, "$source:password")
        for (entry in Lexicon.entries) {
            // The longest matching keyword is the most telling ("namevorname" over "name").
            val hit = entry.keywords.filter { matchesKeyword(squashed, words, it) }.maxByOrNull { it.length } ?: continue
            if (entry.denied.any { matchesKeyword(squashed, words, it) }) continue
            // "code" alone (access code, voucher code, shop code) is weak evidence for a one-time code.
            add(entry.type, if (hit in WEAK_KEYWORDS) weight / 2 else weight, "$source:$hit")
        }
    }

    private fun modelFallback(node: AutofillNode, contextText: List<String>, signals: List<String>): Result? {
        val m = model ?: return null
        if (isMultiLine(node.inputType)) return null
        val (type, margin) = m.predict(FieldFeatures.extract(node, contextText))
        if (type == FieldType.Unknown || margin < (modelMargin ?: m.margin)) return null
        return Result(type, MIN_CONFIDENCE + 10, signals + "model:$type/$margin")
    }

    /**
     * Specific evidence beats generic evidence: "Hausnummer", "Adresszusatz" or "PLZ" next to an
     * "address" id is that part, not the whole address; "Karteninhaber" is not just a name;
     * "E-Mail oder Telefonnummer" asks for any identifier (username), unless typed as e-mail.
     */
    private fun applySpecificity(scores: MutableMap<FieldType, Int>, signals: MutableList<String>, htmlType: String?, declared: Boolean) {
        if (declared) return
        fun has(t: FieldType) = (scores[t] ?: 0) > 0
        fun yieldTo(generic: FieldType, specific: List<FieldType>) {
            if (has(generic) && specific.any(::has)) {
                scores.remove(generic)
                signals += "${generic.name}-yields"
            }
        }
        yieldTo(FieldType.Address, listOf(FieldType.AddressLine2, FieldType.HouseNumber, FieldType.City, FieldType.PostalCode, FieldType.State, FieldType.Country, FieldType.Phone, FieldType.Email, FieldType.FullName, FieldType.Title))
        // "Vollständiger Name (Vor- und Nachname)" names the parts but asks for the whole name.
        val explicitFullName = signals.any { s -> s.startsWith("FullName+") && FULL_NAME_EXPLICIT.any { s.endsWith(":$it") } }
        if (!explicitFullName) {
            yieldTo(FieldType.FullName, listOf(FieldType.CardHolder, FieldType.FirstName, FieldType.LastName, FieldType.Username, FieldType.Organization, FieldType.Title))
        } else {
            scores.remove(FieldType.FirstName)
            scores.remove(FieldType.LastName)
        }
        yieldTo(FieldType.Country, listOf(FieldType.Phone, FieldType.PhoneCountryCode))
        yieldTo(FieldType.Phone, listOf(FieldType.PhoneCountryCode, FieldType.PhoneAreaCode))
        yieldTo(FieldType.CardNumber, listOf(FieldType.CardBrand))
        yieldTo(FieldType.Otp, listOf(FieldType.PostalCode, FieldType.CardCvv, FieldType.CardBrand, FieldType.PhoneAreaCode, FieldType.PhoneCountryCode))
        // "E-Mail oder Telefonnummer / Benutzername": a login identifier, not an e-mail field.
        // Generic words like "login" in "login-email-input" do not make it a multi-identifier field.
        val usernameWords = signals.any { it.startsWith("Username+") && GENERIC_LOGIN_WORDS.none { w -> it.endsWith(":$w") } }
        val exampleEmail = signals.any { it.endsWith(":example-email") || it.endsWith("example-email") }
        if (htmlType != "email" && !exampleEmail && has(FieldType.Email) && (has(FieldType.Phone) || (has(FieldType.Username) && usernameWords))) {
            val best = maxOf(scores[FieldType.Email] ?: 0, scores[FieldType.Username] ?: 0)
            scores[FieldType.Username] = best + 10
            scores.remove(FieldType.Email)
            scores.remove(FieldType.Phone)
            signals += "multi-identifier"
        }
    }

    private fun scoreOptions(options: List<String>, add: (FieldType, Int, String) -> Unit) {
        if (options.isEmpty()) return
        val squashed = options.map { TextNorm.squash(it) }.filter { it.isNotEmpty() }
        fun share(set: Set<String>) = squashed.count { it in set }
        when {
            squashed.count { o -> CARD_BRANDS.any { o.contains(it) } } >= 2 -> add(FieldType.CardBrand, 70, "options:card-brands")
            share(Lexicon.salutationOptions) >= 2 -> add(FieldType.Title, 70, "options:salutation")
            share(Lexicon.countryOptions) >= 2 -> add(FieldType.Country, 70, "options:countries")
            share(Lexicon.stateOptions) >= 2 -> add(FieldType.State, 70, "options:states")
            share(Lexicon.monthOptions) >= 3 -> add(FieldType.CardExpiryMonth, 40, "options:months")
        }
        val numbers = squashed.mapNotNull { it.toIntOrNull() }
        when {
            numbers.size >= 10 && numbers.all { it in 1..12 } -> add(FieldType.CardExpiryMonth, 45, "options:1-12")
            numbers.size >= 5 && numbers.all { it in 2000..2100 } -> add(FieldType.CardExpiryYear, 45, "options:years")
            numbers.size >= 5 && numbers.all { it in 0..99 } && numbers.zipWithNext().all { (a, b) -> b == a + 1 } -> add(FieldType.CardExpiryYear, 25, "options:yy")
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
            FieldType.Email, FieldType.Username, FieldType.CardNumber, FieldType.CardCvv, FieldType.CardBrand, FieldType.CardExpiry,
            FieldType.CardExpiryMonth, FieldType.CardExpiryYear, FieldType.CardHolder, FieldType.FirstName,
            FieldType.LastName, FieldType.FullName, FieldType.PhoneCountryCode, FieldType.PhoneAreaCode, FieldType.PhoneLocal,
            FieldType.Phone, FieldType.PostalCode, FieldType.City,
            FieldType.HouseNumber, FieldType.AddressLine2, FieldType.Street, FieldType.Country, FieldType.State,
            FieldType.Address, FieldType.Organization, FieldType.Title,
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
            "phonenational" to FieldType.Phone,
            "postaladdressstreetaddress" to FieldType.Address,
            "postaladdressextendedaddress" to FieldType.AddressLine2,
            "postaladdressaptnumber" to FieldType.AddressLine2,
            "postaladdresslocality" to FieldType.City,
            "postaladdressregion" to FieldType.State,
            "postaladdresscountry" to FieldType.Country,
            "postaladdresspostalcode" to FieldType.PostalCode,
            "personnameprefix" to FieldType.Title,
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
            "address-line2" to FieldType.AddressLine2,
            "address-line3" to FieldType.AddressLine2,
            "postal-code" to FieldType.PostalCode,
            "address-level2" to FieldType.City,
            "address-level1" to FieldType.State,
            "country" to FieldType.Country,
            "country-name" to FieldType.Country,
            "organization" to FieldType.Organization,
            "honorific-prefix" to FieldType.Title,
            "tel-local" to FieldType.PhoneLocal,
            "tel-local-prefix" to FieldType.PhoneLocal,
            "tel-local-suffix" to FieldType.PhoneLocal,
            "tel-country-code" to FieldType.PhoneCountryCode,
            "tel-area-code" to FieldType.PhoneAreaCode,
            "cc-type" to FieldType.CardBrand,
            "cc-given-name" to FieldType.CardHolder,
            "cc-family-name" to FieldType.CardHolder,
            // Common misspellings seen in the wild (Stripe Checkout uses "ccname").
            "ccname" to FieldType.CardHolder,
        )

        /** Attributes in which Chrome hands its own type prediction to autofill services. */
        private val CHROME_PREDICTION_ATTRS = listOf("crowdsourcing-autofill-hints", "computed-autofill-hints", "ua-autofill-hints")

        /** Chrome `FieldType` names → ours. */
        private val CHROME_TYPES: Map<String, FieldType> = mapOf(
            "USERNAME" to FieldType.Username,
            "USERNAME_AND_EMAIL_ADDRESS" to FieldType.Username,
            "EMAIL_ADDRESS" to FieldType.Email,
            "PASSWORD" to FieldType.Password,
            "ACCOUNT_CREATION_PASSWORD" to FieldType.NewPassword,
            "NEW_PASSWORD" to FieldType.NewPassword,
            "CONFIRMATION_PASSWORD" to FieldType.ConfirmPassword,
            "ONE_TIME_CODE" to FieldType.Otp,
            "NAME_FULL" to FieldType.FullName,
            "NAME_FIRST" to FieldType.FirstName,
            "NAME_LAST" to FieldType.LastName,
            "NAME_HONORIFIC_PREFIX" to FieldType.Title,
            "PHONE_HOME_WHOLE_NUMBER" to FieldType.Phone,
            "PHONE_HOME_CITY_AND_NUMBER" to FieldType.Phone,
            "PHONE_HOME_NUMBER" to FieldType.Phone,
            "ADDRESS_HOME_LINE1" to FieldType.Address,
            "ADDRESS_HOME_STREET_ADDRESS" to FieldType.Address,
            "ADDRESS_HOME_LINE2" to FieldType.AddressLine2,
            "ADDRESS_HOME_APT_NUM" to FieldType.AddressLine2,
            "ADDRESS_HOME_STREET_NAME" to FieldType.Street,
            "ADDRESS_HOME_HOUSE_NUMBER" to FieldType.HouseNumber,
            "ADDRESS_HOME_CITY" to FieldType.City,
            "ADDRESS_HOME_STATE" to FieldType.State,
            "ADDRESS_HOME_ZIP" to FieldType.PostalCode,
            "ADDRESS_HOME_COUNTRY" to FieldType.Country,
            "COMPANY_NAME" to FieldType.Organization,
            "CREDIT_CARD_NAME_FULL" to FieldType.CardHolder,
            "CREDIT_CARD_NUMBER" to FieldType.CardNumber,
            "CREDIT_CARD_EXP_MONTH" to FieldType.CardExpiryMonth,
            "CREDIT_CARD_EXP_2_DIGIT_YEAR" to FieldType.CardExpiryYear,
            "CREDIT_CARD_EXP_4_DIGIT_YEAR" to FieldType.CardExpiryYear,
            "CREDIT_CARD_EXP_DATE_2_DIGIT_YEAR" to FieldType.CardExpiry,
            "CREDIT_CARD_EXP_DATE_4_DIGIT_YEAR" to FieldType.CardExpiry,
            "CREDIT_CARD_VERIFICATION_CODE" to FieldType.CardCvv,
            "CREDIT_CARD_TYPE" to FieldType.CardBrand,
            "PHONE_HOME_COUNTRY_CODE" to FieldType.PhoneCountryCode,
            "PHONE_HOME_CITY_CODE" to FieldType.PhoneAreaCode,
            "PHONE_HOME_CITY_CODE_WITH_TRUNK_PREFIX" to FieldType.PhoneAreaCode,
            "PHONE_HOME_NUMBER_PREFIX" to FieldType.PhoneLocal,
            "PHONE_HOME_NUMBER_SUFFIX" to FieldType.PhoneLocal,
        )

        /** Short keywords (≤ 4 chars) must be a whole word; longer ones may be a substring. */
        internal fun matchesKeyword(squashed: String, words: Set<String>, keyword: String): Boolean = when {
            keyword.length > 4 -> squashed.contains(keyword)
            keyword in words || squashed == keyword -> true
            // A few short keywords are safe as word endings: "billingcity", "shippingzip", "cardcvc".
            keyword in SUFFIX_KEYWORDS -> words.any { it.endsWith(keyword) }
            else -> false
        }

        private val FULL_NAME_EXPLICIT = setOf(
            "fullname", "vollstaendigername", "nomcomplet", "nombrecompleto", "nomecompleto", "yourname", "ihrname",
            "vorundnachname", "namevorname", "nachnamevorname", "vornamename", "vornameundname", "vornameundnachname", "firstandlastname",
        )

        /** Below this score a rule decision rests on label words alone. */
        private const val WEAK_RULE = 45

        private val WEAK_KEYWORDS = setOf("code", "token")

        private val CARD_BRANDS = listOf("visa", "mastercard", "americanexpress", "amex", "discover", "maestro", "dinersclub", "jcb")

        /** Valid field names we deliberately leave empty (birthday, extension, …). */
        private val AUTOCOMPLETE_NOT_FILLED = setOf(
            "bday", "bday-day", "bday-month", "bday-year", "sex", "url", "photo", "impp", "nickname", "language",
            "additional-name", "honorific-suffix", "organization-title", "tel-extension", "cc-additional-name",
            "transaction-currency", "transaction-amount", "address-level3", "address-level4",
        )

        private val AUTOCOMPLETE_MODIFIERS = setOf("shipping", "billing", "home", "work", "mobile", "fax", "pager", "webauthn")

        /** The field name of a valid autocomplete attribute, or null if absent or invalid. */
        internal fun parseAutocomplete(raw: String?): String? {
            val tokens = raw?.trim()?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.filter { it != "webauthn" } ?: return null
            if (tokens.isEmpty()) return null
            val name = tokens.last()
            // "off" only asks browsers not to remember the value; password managers fill anyway.
            if (name == "on" || name == "off") return null
            val known = name in AUTOCOMPLETE || name in AUTOCOMPLETE_NOT_FILLED
            val modifiersOk = tokens.dropLast(1).all { it.startsWith("section-") || it in AUTOCOMPLETE_MODIFIERS }
            return if (known && modifiersOk) name else null
        }

        private val GENERIC_LOGIN_WORDS = setOf("login", "signin", "account", "identifier", "anmeldung", "user")

        private val EXAMPLE_EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[a-z]{2,}")

        private val SUFFIX_KEYWORDS = setOf("city", "zip", "plz", "cvc", "cvv", "csc")

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
