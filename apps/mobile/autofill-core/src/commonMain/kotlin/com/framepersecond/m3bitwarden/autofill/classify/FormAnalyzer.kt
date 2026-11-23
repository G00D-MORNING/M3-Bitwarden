package com.framepersecond.m3bitwarden.autofill.classify

import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.model.DetectedField
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

    fun analyze(root: AutofillNode): FormAnalysis {
        val inputs = ArrayList<Visited>()
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
                NodeKind.TextInput -> {
                    if (node.id != null) inputs += Visited(node, counter++, labelsFor(node, siblingsBefore), form, d, s)
                }
                NodeKind.Button -> listOfNotNull(node.text, node.contentDescription, node.idEntry, node.htmlAttributes["value"]).forEach { buttonTexts += it }
                NodeKind.Label -> node.text?.let { pageTexts += it }
                else -> Unit
            }
            node.children.forEachIndexed { i, child -> walk(child, form, d, s, node.children.subList(0, i)) }
        }
        walk(root, 0, null, null, emptyList())

        val raw = inputs.map { v ->
            val r = classifier.classify(v.node, v.labels)
            v to r
        }
        var fields = raw.filter { it.second.type != FieldType.Unknown }.map { (v, r) -> v.toDetected(r.type, r.confidence, r.signals) }
            .toMutableList()

        fields = applyOtpDigits(raw.map { it.first }, fields)
        fields = applyPasswordPairs(fields)
        fields = applyUsernameBeforePassword(raw, fields)
        fields = applyCardContext(fields)
        fields.sortBy { it.order }

        val intent = decideIntent(fields, buttonTexts + pageTexts)
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
    )

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
        val passwords = fields.filter { it.type.isPassword }.sortedBy { it.order }
        if (passwords.size < 2) return fields
        val byId = fields.associateBy { it.id }.toMutableMap()
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
            .filter { (_, r) -> r.signals.none { it.startsWith("denied") || it.startsWith("html type") || it == "multiline" } }
            .filter { (v, _) -> v.node.htmlAttributes["type"].let { it == null || it == "text" || it == "email" || it == "tel" } }
            .maxByOrNull { it.first.order }
            ?: return fields
        fields += candidate.first.toDetected(FieldType.Username, 45, listOf("nearest-before-password"))
        return fields
    }

    private fun applyCardContext(fields: MutableList<DetectedField>): MutableList<DetectedField> {
        if (fields.any { it.type == FieldType.CardNumber }) return fields
        return fields.filterNot {
            it.type == FieldType.CardExpiryMonth || it.type == FieldType.CardExpiryYear ||
                it.type == FieldType.CardExpiry || it.type == FieldType.CardCvv
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
