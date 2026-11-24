package com.framepersecond.m3bitwarden.autofill.classify

import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.model.DetectedField
import com.framepersecond.m3bitwarden.autofill.model.FieldFormat
import com.framepersecond.m3bitwarden.autofill.model.FieldType
import com.framepersecond.m3bitwarden.autofill.model.NodeKind
import com.framepersecond.m3bitwarden.autofill.text.TextNorm

/** What the user is trying to do on this screen. */
enum class FormIntent {
    /** Username and password together. */
    Login,

    /** First step of a multi-step login: only the username or e-mail is asked. */
    UsernameStep,

    /** Second step: only the password is asked, the username was on the previous screen. */
    PasswordStep,
    Signup,
    ChangePassword,
    OneTimeCode,
    Card,
    Identity,
    Unknown,
}

data class FormAnalysis(
    val fields: List<DetectedField>,
    val intent: FormIntent,
    /** Domain the page claims to be, if the screen shows web content. */
    val webDomain: String?,
    val webScheme: String?,
    val focusedFieldId: String?,
) {
    fun firstOf(vararg types: FieldType): DetectedField? = fields.firstOrNull { it.type in types }
    fun allOf(vararg types: FieldType): List<DetectedField> = fields.filter { it.type in types }
    val hasFillableFields: Boolean get() = fields.isNotEmpty()
}

/**
 * Walks the screen, classifies every input with its surrounding labels and then applies
 * form-level rules that single-field heuristics cannot see:
 *
 * - two password fields → new + confirm (sign-up) or current + new (change),
 * - an unlabelled text field right before a password is the username,
 * - a row of single-character inputs is a split one-time code,
 * - card expiry fields only count next to a card number (as Proton Pass does),
 * - button texts like "Weiter" or "Registrieren" decide between login steps and sign-up.
 */
class FormAnalyzer(private val classifier: FieldClassifier = FieldClassifier()) {

    private data class Visited(
        val node: AutofillNode,
        val order: Int,
        val labels: List<String>,
        val formIndex: Int,
        val webDomain: String?,
        val webScheme: String?,
    )

    private class Collected(val inputs: List<Visited>, val texts: List<String>, val formTexts: Map<Int, List<String>>)

    private fun collect(root: AutofillNode): Collected {
        val inputs = ArrayList<Visited>()
        val formTexts = HashMap<Int, MutableList<String>>()
        val buttonTexts = ArrayList<String>()
        val pageTexts = ArrayList<String>()
        var counter = 0
        var formCounter = 0

        fun walk(node: AutofillNode, formIndex: Int, domain: String?, scheme: String?, siblingsBefore: List<AutofillNode>) {
            if (!node.isVisible) return
            val d = node.webDomain?.takeIf { it.isNotBlank() } ?: domain
            val s = node.webScheme?.takeIf { it.isNotBlank() } ?: scheme
            val form = if (node.htmlTag == "form") ++formCounter else formIndex
            when (node.kind) {
                NodeKind.TextInput, NodeKind.Select -> {
                    if (node.id != null) inputs += Visited(node, counter++, labelsFor(node, siblingsBefore), form, d, s)
                }
                NodeKind.Button -> listOfNotNull(node.text, node.contentDescription, node.idEntry, node.htmlAttributes["value"]).forEach {
                    buttonTexts += it
                    formTexts.getOrPut(form) { ArrayList() } += it
                }
                NodeKind.Label -> node.text?.let { pageTexts += it }
                else -> Unit
            }
            node.children.forEachIndexed { i, child -> walk(child, form, d, s, node.children.subList(0, i)) }
        }
        walk(root, 0, null, null, emptyList())
        return Collected(inputs, buttonTexts + pageTexts, formTexts)
    }

    /** Every input with the labels the classifier sees; used to train [LearnedModel]. */
    internal fun labelledInputs(root: AutofillNode): List<Pair<AutofillNode, List<String>>> =
        collect(root).inputs.map { it.node to it.labels }

    fun analyze(root: AutofillNode): FormAnalysis {
        val collected = collect(root)
        val inputs = collected.inputs
        val raw = inputs.map { v ->
            val r = classifier.classify(v.node, v.labels)
            v to r
        }
        var fields = raw.filter { it.second.type != FieldType.Unknown }.map { (v, r) -> v.toDetected(r.type, r.confidence, r.signals) }
            .toMutableList()

        fields = applyModelCoherence(fields)
        fields = applyOtpDigits(raw.map { it.first }, fields)
        fields = applyPasswordPairs(fields)
        fields = applySignupPassword(fields, collected.formTexts)
        fields = applyUsernameBeforePassword(raw, fields)
        fields = applyCardContext(fields)
        fields = applyAddressParts(fields)
        fields = applyNameParts(fields)
        fields = applyPhoneParts(fields)
        fields = applyExpiryPair(fields)
        fields.sortBy { it.order }

        val intent = decideIntent(fields, collected.texts)
        val focused = raw.firstOrNull { it.first.node.isFocused }?.first?.node?.id
        val domain = raw.firstOrNull { it.first.node.isFocused }?.first?.webDomain ?: raw.firstNotNullOfOrNull { it.first.webDomain } ?: root.webDomain
        val scheme = raw.firstOrNull { it.first.node.isFocused }?.first?.webScheme ?: raw.firstNotNullOfOrNull { it.first.webScheme } ?: root.webScheme
        return FormAnalysis(fields, intent, domain, scheme, focused)
    }

    private fun labelsFor(node: AutofillNode, siblingsBefore: List<AutofillNode>): List<String> {
        // The nearest two labels before the input in the same parent usually describe it.
        val labels = siblingsBefore.asReversed()
            .asSequence()
            .takeWhile { it.kind != NodeKind.TextInput }
            .mapNotNull { sibling -> sibling.text ?: sibling.contentDescription ?: sibling.children.firstNotNullOfOrNull { it.text } }
            .take(2)
            .toList()
        return labels
    }

    private fun Visited.toDetected(type: FieldType, confidence: Int, signals: List<String>) = DetectedField(
        id = node.id!!,
        type = type,
        confidence = confidence,
        signals = signals,
        isFocused = node.isFocused,
        order = order,
        maxLength = node.maxLength,
        webDomain = webDomain,
        webScheme = webScheme,
        formIndex = formIndex,
        format = FieldFormat(
            placeholder = node.htmlAttributes["placeholder"] ?: node.hint,
            maxLength = node.maxLength,
            options = node.options,
            isSelect = node.isSelect,
        ),
    )

    /**
     * The learned model only fills gaps the rules left. In a form with a password it may only
     * name credential fields, so a "Zugangsnummer" is never taken for a house number.
     */
    private fun applyModelCoherence(fields: MutableList<DetectedField>): MutableList<DetectedField> {
        val hasPassword = fields.any { it.type.isPassword && it.signals.none { s -> s.startsWith("model:") } }
        if (!hasPassword) return fields
        val allowed = setOf(FieldType.Username, FieldType.Email, FieldType.Otp, FieldType.OtpDigit, FieldType.Password, FieldType.NewPassword, FieldType.ConfirmPassword)
        return fields.filterNot { f -> f.signals.any { it.startsWith("model:") } && f.type !in allowed }.toMutableList()
    }

    private fun applyOtpDigits(all: List<Visited>, fields: MutableList<DetectedField>): MutableList<DetectedField> {
        // Four to eight consecutive single-character inputs form one code.
        val runs = ArrayList<List<Visited>>()
        var current = ArrayList<Visited>()
        for (v in all) {
            if (v.node.maxLength == 1) current += v else {
                if (current.size >= 4) runs += current
                current = ArrayList()
            }
        }
        if (current.size >= 4) runs += current
        if (runs.isEmpty()) return fields
        val digitIds = runs.flatten().associateBy { it.node.id }
        val kept = fields.filter { it.id !in digitIds }.toMutableList()
        runs.flatten().forEach { kept += it.toDetected(FieldType.OtpDigit, 80, listOf("otp-run")) }
        return kept
    }

    private fun applyPasswordPairs(fields: MutableList<DetectedField>): MutableList<DetectedField> {
        val byId = fields.associateBy { it.id }.toMutableMap()
        // Login and sign-up side by side on one page are two forms: pair passwords per form only.
        for ((_, inForm) in fields.groupBy { it.formIndex }) {
            val passwords = inForm.filter { it.type.isPassword }.sortedBy { it.order }
            if (passwords.size < 2) continue
            val explicitCurrent = passwords.firstOrNull { it.type == FieldType.Password && it.signals.any { s -> s.contains("current") } }
            when {
                // Current + new (+ confirm): change-password form.
                passwords.size >= 3 || explicitCurrent != null -> {
                    val current = explicitCurrent ?: passwords[0]
                    val rest = passwords.filter { it.id != current.id }
                    byId[current.id] = current.copy(type = FieldType.Password)
                    rest.getOrNull(0)?.let { byId[it.id] = it.copy(type = FieldType.NewPassword) }
                    rest.drop(1).forEach { byId[it.id] = it.copy(type = FieldType.ConfirmPassword) }
                }
                // Exactly two without "current" evidence: sign-up (new + confirm).
                else -> {
                    val (first, second) = passwords
                    if (first.type != FieldType.ConfirmPassword) byId[first.id] = first.copy(type = FieldType.NewPassword)
                    byId[second.id] = second.copy(type = FieldType.ConfirmPassword)
                }
            }
        }
        return byId.values.toMutableList()
    }

    /**
     * One password field in a form whose own button says "Registrieren", "Konto erstellen",
     * "Sign up" or "Join": a new password (offer to generate one, never an old one).
     */
    private fun applySignupPassword(fields: MutableList<DetectedField>, formTexts: Map<Int, List<String>>): MutableList<DetectedField> {
        val byId = fields.associateBy { it.id }.toMutableMap()
        for ((form, inForm) in fields.groupBy { it.formIndex }) {
            val passwords = inForm.filter { it.type == FieldType.Password }
            if (passwords.size != 1 || inForm.any { it.type == FieldType.NewPassword || it.type == FieldType.ConfirmPassword }) continue
            val p = passwords.single()
            if (p.signals.any { it.contains("autocomplete=current-password") || it.contains("current-marker") }) continue
            val texts = formTexts[form].orEmpty().map { TextNorm.squash(it) }
            val signup = texts.any { t -> Lexicon.signupWords.any { t.contains(it) } }
            val login = texts.any { t -> Lexicon.loginWords.any { t.contains(it) } }
            if (signup && !login) byId[p.id] = p.copy(type = FieldType.NewPassword, signals = p.signals + "signup-button")
        }
        return byId.values.toMutableList()
    }

    private fun applyUsernameBeforePassword(
        raw: List<Pair<Visited, FieldClassifier.Result>>,
        fields: MutableList<DetectedField>,
    ): MutableList<DetectedField> {
        if (fields.any { it.type.isIdentifier }) return fields
        val password = fields.filter { it.type.isPassword }.minByOrNull { it.order } ?: return fields
        // The closest earlier input in the same form that we could not classify.
        val candidate = raw
            .filter { (v, r) -> v.order < password.order && r.type == FieldType.Unknown }
            .filter { (_, r) -> r.signals.none { it.startsWith("denied") || it.startsWith("html type") || it == "multiline" || it == "readonly" } }
            .filter { (v, _) -> !v.node.isSelect }
            .filter { (v, _) -> v.node.htmlAttributes["type"].let { it == null || it == "text" || it == "email" || it == "tel" } }
            .maxByOrNull { it.first.order }
            ?: return fields
        fields += candidate.first.toDetected(FieldType.Username, 45, listOf("nearest-before-password"))
        return fields
    }

    private fun applyCardContext(fields: MutableList<DetectedField>): MutableList<DetectedField> {
        if (fields.any { it.type == FieldType.CardNumber }) {
            // Shops often mask the card code as a password field: next to a card number, a
            // "password" that mentions CVC/Prüfnummer or allows at most 4 characters is the code.
            return fields.map { f ->
                val cvvHint = f.signals.any { it.startsWith("CardCvv+") }
                if (f.type == FieldType.Password && (cvvHint || f.maxLength in 3..4)) f.copy(type = FieldType.CardCvv, signals = f.signals + "card-context-cvv") else f
            }.toMutableList()
        }
        return fields.filterNot {
            it.type == FieldType.CardExpiryMonth || it.type == FieldType.CardExpiryYear ||
                it.type == FieldType.CardExpiry || it.type == FieldType.CardCvv
        }.toMutableList()
    }

    /**
     * Split phone numbers: "Vorwahl" + number, or the US layout of three boxes (3 | 3 | 4 digits).
     * The number next to an area code is the local part.
     */
    private fun applyPhoneParts(fields: MutableList<DetectedField>): MutableList<DetectedField> {
        val sorted = fields.sortedBy { it.order }
        val byId = sorted.associateBy { it.id }.toMutableMap()
        val phones = sorted.filter { it.type == FieldType.Phone }
        // Three phone boxes in a row with short limits.
        phones.windowed(3).forEach { (a, b, c) ->
            if (b.order == a.order + 1 && c.order == b.order + 1 && a.maxLength in 2..4 && b.maxLength in 3..4 && c.maxLength in 4..5) {
                byId[a.id] = a.copy(type = FieldType.PhoneAreaCode, signals = a.signals + "phone-3-3-4")
                byId[b.id] = b.copy(type = FieldType.PhoneLocal, signals = b.signals + "phone-3-3-4")
                byId[c.id] = c.copy(type = FieldType.PhoneLocal, signals = c.signals + "phone-3-3-4")
            }
        }
        // A phone field right after an area code (or country code) is the remaining number.
        sorted.forEach { f ->
            val cur = byId.getValue(f.id)
            if (cur.type != FieldType.Phone) return@forEach
            val prev = sorted.lastOrNull { it.order < f.order }?.let { byId[it.id] }
            if (prev != null && prev.order == f.order - 1 && prev.type == FieldType.PhoneAreaCode) {
                byId[f.id] = cur.copy(type = FieldType.PhoneLocal, signals = cur.signals + "after-area-code")
            }
        }
        // Two short phone boxes: area code and number.
        val stillPhone = sorted.map { byId.getValue(it.id) }.filter { it.type == FieldType.Phone }
        stillPhone.zipWithNext().forEach { (a, b) ->
            if (b.order == a.order + 1 && a.maxLength in 2..6 && (b.maxLength == -1 || b.maxLength > a.maxLength)) {
                byId[a.id] = a.copy(type = FieldType.PhoneAreaCode, signals = a.signals + "short-before-phone")
                byId[b.id] = b.copy(type = FieldType.PhoneLocal, signals = b.signals + "short-before-phone")
            }
        }
        return byId.values.toMutableList()
    }

    /** "Gültig bis" over two boxes or lists: the first is the month, the second the year. */
    private fun applyExpiryPair(fields: MutableList<DetectedField>): MutableList<DetectedField> {
        val sorted = fields.sortedBy { it.order }
        val byId = sorted.associateBy { it.id }.toMutableMap()
        val expiryish = setOf(FieldType.CardExpiry, FieldType.CardExpiryMonth, FieldType.CardExpiryYear)
        sorted.zipWithNext().forEach { (a, b) ->
            if (b.order != a.order + 1 || a.type !in expiryish || b.type !in expiryish) return@forEach
            if (a.type == FieldType.CardExpiryMonth && b.type == FieldType.CardExpiryYear) return@forEach
            if (a.type == FieldType.CardExpiry && b.type == FieldType.CardExpiry && a.maxLength !in 1..2 && !a.format.isSelect) return@forEach
            byId[a.id] = a.copy(type = FieldType.CardExpiryMonth, signals = a.signals + "expiry-pair")
            byId[b.id] = b.copy(type = FieldType.CardExpiryYear, signals = b.signals + "expiry-pair")
        }
        return byId.values.toMutableList()
    }

    /** "Vorname" next to a plain "Name" field: that one is the family name (Swiss and official forms). */
    private fun applyNameParts(fields: MutableList<DetectedField>): MutableList<DetectedField> {
        val first = fields.filter { it.type == FieldType.FirstName }
        if (first.isEmpty() || fields.any { it.type == FieldType.LastName }) return fields
        return fields.map { f ->
            val plainName = f.type == FieldType.FullName && f.signals.all { s -> !s.startsWith("FullName+") || s.endsWith(":name") }
            val adjacent = first.any { kotlin.math.abs(it.order - f.order) == 1 }
            if (plainName && adjacent) f.copy(type = FieldType.LastName, signals = f.signals + "name-next-to-first-name") else f
        }.toMutableList()
    }

    /** With a separate house-number field, the "address" field holds only the street name. */
    private fun applyAddressParts(fields: MutableList<DetectedField>): MutableList<DetectedField> {
        val houseNumber = fields.filter { it.type == FieldType.HouseNumber }
        if (houseNumber.isEmpty()) return fields
        return fields.map { f ->
            val near = houseNumber.any { h -> kotlin.math.abs(h.order - f.order) <= 3 }
            if (f.type == FieldType.Address && near) f.copy(type = FieldType.Street, signals = f.signals + "house-number-nearby") else f
        }.toMutableList()
    }

    private fun decideIntent(fields: List<DetectedField>, texts: List<String>): FormIntent {
        val types = fields.map { it.type }.toSet()
        val squashedTexts = texts.map { TextNorm.squash(it) }
        fun anyText(words: Set<String>) = squashedTexts.any { t -> words.any { t.contains(it) } }

        val hasPassword = FieldType.Password in types
        val hasNew = FieldType.NewPassword in types || FieldType.ConfirmPassword in types
        val hasIdentifier = types.any { it.isIdentifier }
        return when {
            hasPassword && hasNew -> FormIntent.ChangePassword
            hasNew && !hasIdentifier && anyText(Lexicon.changePasswordWords) -> FormIntent.ChangePassword
            hasNew -> FormIntent.Signup
            hasPassword && hasIdentifier -> if (anyText(Lexicon.signupWords) && !anyText(Lexicon.loginWords)) FormIntent.Signup else FormIntent.Login
            hasPassword -> FormIntent.PasswordStep
            FieldType.Otp in types || FieldType.OtpDigit in types -> FormIntent.OneTimeCode
            types.any { it.isCard } -> FormIntent.Card
            hasIdentifier && (anyText(Lexicon.nextWords) || anyText(Lexicon.loginWords) || types.size == 1) -> FormIntent.UsernameStep
            types.isNotEmpty() -> FormIntent.Identity
            else -> FormIntent.Unknown
        }
    }
}
