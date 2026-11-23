package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.autofill.classify.InputTypeBits
import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.model.NodeKind

/** Small builders so tests read like the screens they describe. */
internal object Nodes {
    const val TEXT = InputTypeBits.CLASS_TEXT
    const val PASSWORD = InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_PASSWORD
    const val EMAIL = InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_EMAIL_ADDRESS
    const val NUMBER_PASSWORD = InputTypeBits.CLASS_NUMBER or InputTypeBits.NUMBER_VARIATION_PASSWORD
    const val NUMBER = InputTypeBits.CLASS_NUMBER

    fun input(
        id: String,
        inputType: Int = TEXT,
        idEntry: String? = null,
        hint: String? = null,
        hints: List<String> = emptyList(),
        html: Map<String, String> = emptyMap(),
        maxLength: Int = -1,
        focused: Boolean = false,
    ) = AutofillNode(
        id = id,
        kind = NodeKind.TextInput,
        inputType = inputType,
        idEntry = idEntry,
        hint = hint,
        autofillHints = hints,
        htmlTag = if (html.isNotEmpty()) "input" else null,
        htmlAttributes = html,
        maxLength = maxLength,
        isFocused = focused,
    )

    fun label(text: String) = AutofillNode(id = null, kind = NodeKind.Label, text = text)
    fun button(text: String) = AutofillNode(id = "btn-$text", kind = NodeKind.Button, text = text)

    fun screen(vararg children: AutofillNode, domain: String? = null, scheme: String? = null) =
        AutofillNode(id = "root", kind = NodeKind.Container, children = children.toList(), webDomain = domain, webScheme = scheme)

    fun form(vararg children: AutofillNode) =
        AutofillNode(id = null, kind = NodeKind.Container, htmlTag = "form", children = children.toList())
}
