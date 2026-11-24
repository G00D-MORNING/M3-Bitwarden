package com.framepersecond.m3bitwarden.autofill.corpus

import com.framepersecond.m3bitwarden.autofill.classify.InputTypeBits
import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.model.FieldType
import com.framepersecond.m3bitwarden.autofill.model.NodeKind
import java.io.File

/**
 * Loads real-world forms collected by the scripts in `autofill-core/training/` (Chromium's
 * labelled corpus, pages crawled with headless Chrome). One TSV line per field; fields arrive
 * the way browsers report them to an Android autofill service.
 */
internal object TsvCorpus {
    fun load(path: String): List<CorpusForm> {
        val file = File(path)
        if (!file.exists()) return emptyList()
        val lines = file.readLines()
        val header = lines.first().split('\t')
        fun col(row: List<String>, name: String) = row.getOrElse(header.indexOf(name)) { "" }
        val byForm = LinkedHashMap<String, MutableList<List<String>>>()
        lines.drop(1).filter { it.isNotBlank() }.map { it.split('\t') }.forEach { row -> byForm.getOrPut(col(row, "form")) { ArrayList() } += row }
        return byForm.map { (form, rows) ->
            val groups = LinkedHashMap<String, MutableList<AutofillNode>>()
            val expected = LinkedHashMap<String, FieldType>()
            rows.forEachIndexed { i, row ->
                val nodes = groups.getOrPut(col(row, "formidx").ifEmpty { "0" }) { ArrayList() }
                val key = "f$i"
                val attrs = buildMap {
                    for (name in listOf("type", "name", "id", "autocomplete", "placeholder", "label", "maxlength")) {
                        col(row, name).lowercase().takeIf { it.isNotEmpty() }?.let { put(name, it) }
                    }
                    col(row, "aria").lowercase().takeIf { it.isNotEmpty() }?.let { put("aria-label", it) }
                }
                val select = col(row, "kind") == "select"
                nodes += AutofillNode(
                    id = key,
                    kind = if (select) NodeKind.Select else NodeKind.TextInput,
                    htmlTag = if (select) "select" else "input",
                    htmlAttributes = attrs,
                    hint = attrs["placeholder"],
                    inputType = if (select) 0 else InputTypeBits.CLASS_TEXT,
                    maxLength = attrs["maxlength"]?.toIntOrNull() ?: -1,
                    options = col(row, "options").split('|').filter { it.isNotBlank() },
                )
                // "Skip": present on the page but not scored (credential fields in an address corpus).
                val label = col(row, "expected")
                if (label != "Skip" && label.isNotEmpty()) expected[key] = runCatching { FieldType.valueOf(label) }.getOrDefault(FieldType.Unknown)
            }
            // One <form> per form index (fields outside any form share a plain container), with its buttons.
            val formNodes = groups.map { (idx, nodes) ->
                val buttons = rows.firstOrNull { col(it, "formidx").ifEmpty { "0" } == idx }?.let { col(it, "formbuttons") }.orEmpty()
                    .split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    .mapIndexed { bi, text -> AutofillNode(id = "b$idx-$bi", kind = NodeKind.Button, text = text) }
                AutofillNode(id = null, kind = NodeKind.Container, htmlTag = if (idx == "-1") null else "form", children = nodes + buttons)
            }
            CorpusForm(col(rows.first(), "source"), AutofillNode(id = "root", kind = NodeKind.Container, children = formNodes, webDomain = "example.com", webScheme = "https"), expected, null)
        }
    }
}
