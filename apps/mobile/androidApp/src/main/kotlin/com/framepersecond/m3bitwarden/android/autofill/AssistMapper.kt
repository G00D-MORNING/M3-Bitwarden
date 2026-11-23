package com.framepersecond.m3bitwarden.android.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.view.View
import android.view.autofill.AutofillId
import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.model.NodeKind

/**
 * Converts Android's view tree into the platform-neutral [AutofillNode] and remembers which
 * opaque id stands for which [AutofillId], so the fill response can address the views.
 *
 * Typed values (`autofillValue`) are read only by [textValue], which is used for save requests.
 */
class AssistMapper {
    private val ids = HashMap<String, AutofillId>()
    private val nodes = HashMap<String, AssistStructure.ViewNode>()
    private var counter = 0

    fun autofillId(id: String): AutofillId? = ids[id]

    fun textValue(id: String): String? {
        val value = nodes[id]?.autofillValue ?: return null
        return if (value.isText) value.textValue.toString() else null
    }

    fun map(structure: AssistStructure): AutofillNode {
        val windows = (0 until structure.windowNodeCount).map { structure.getWindowNodeAt(it).rootViewNode }
        return AutofillNode(id = null, kind = NodeKind.Container, children = windows.map(::mapNode))
    }

    private fun mapNode(n: AssistStructure.ViewNode): AutofillNode {
        val key = n.autofillId?.let { aid ->
            val k = "v${counter++}"
            ids[k] = aid
            nodes[k] = n
            k
        }
        val html = n.htmlInfo
        val attrs: Map<String, String> = html?.attributes
            ?.mapNotNull { p -> p.first?.lowercase()?.let { k -> k to (p.second ?: "").lowercase() } }
            ?.toMap()
            .orEmpty()
        val tag = html?.tag?.lowercase()
        return AutofillNode(
            id = key,
            kind = kindOf(n, tag, attrs),
            inputType = n.inputType,
            autofillHints = n.autofillHints?.filterNotNull()?.map { it.lowercase() }.orEmpty(),
            htmlTag = tag,
            htmlAttributes = attrs,
            // Labels and buttons only: never the text of an input, which is what the user typed.
            text = if (isInput(n, tag, attrs)) null else n.text?.toString(),
            hint = n.hint ?: n.autofillOptions?.joinToString(" "),
            idEntry = n.idEntry,
            contentDescription = n.contentDescription?.toString(),
            isFocused = n.isFocused,
            isVisible = n.visibility == View.VISIBLE,
            isImportantForAutofill = importantForAutofill(n),
            maxLength = attrs["maxlength"]?.toIntOrNull()
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) n.maxTextLength else -1,
            webDomain = n.webDomain,
            webScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) n.webScheme else null,
            children = (0 until n.childCount).map { mapNode(n.getChildAt(it)) },
        )
    }

    private fun kindOf(n: AssistStructure.ViewNode, tag: String?, attrs: Map<String, String>): NodeKind = when {
        isInput(n, tag, attrs) -> NodeKind.TextInput
        tag == "button" || (tag == "input" && attrs["type"] in setOf("submit", "button")) -> NodeKind.Button
        n.className?.endsWith("Button") == true -> NodeKind.Button
        n.className?.endsWith("TextView") == true || tag == "label" -> NodeKind.Label
        n.childCount > 0 -> NodeKind.Container
        else -> NodeKind.Other
    }

    private fun isInput(n: AssistStructure.ViewNode, tag: String?, attrs: Map<String, String>): Boolean {
        if (tag == "input") return attrs["type"] !in setOf("hidden", "submit", "button", "checkbox", "radio", "image", "reset", "file")
        if (tag == "textarea" || tag == "select") return false
        // Native: any view the system can autofill with text, plus classic EditTexts
        // (Compose text fields report as EditText-like views with autofillType TEXT).
        return n.autofillType == View.AUTOFILL_TYPE_TEXT ||
            n.className?.let { it.endsWith("EditText") || it.endsWith("AutoCompleteTextView") } == true
    }

    private fun importantForAutofill(n: AssistStructure.ViewNode): Boolean =
        n.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_NO &&
            n.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
}
