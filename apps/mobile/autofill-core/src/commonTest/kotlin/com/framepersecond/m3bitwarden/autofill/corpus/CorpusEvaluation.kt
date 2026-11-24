package com.framepersecond.m3bitwarden.autofill.corpus

import com.framepersecond.m3bitwarden.autofill.classify.FormAnalyzer
import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.model.FieldType

/** Accuracy of the analyzer on the corpus, with every mistake listed. */
internal data class Evaluation(
    val fields: Int,
    val correctFields: Int,
    val forms: Int,
    val correctIntents: Int,
    val mistakes: List<String>,
    val perType: Map<FieldType, Pair<Int, Int>>,
    /** Correct for filling: username and e-mail fields receive the same login value. */
    val functionalCorrect: Int = correctFields,
) {
    val fieldAccuracy: Double get() = correctFields * 100.0 / fields
    val intentAccuracy: Double get() = correctIntents * 100.0 / forms

    fun report(): String = buildString {
        appendLine("Felder: $correctFields/$fields richtig (${fmt(fieldAccuracy)} %), beim Ausfüllen richtig: $functionalCorrect (${fmt(functionalCorrect * 100.0 / fields)} %)")
        appendLine("Absicht: $correctIntents/$forms richtig (${fmt(intentAccuracy)} %)")
        perType.entries.sortedBy { it.key.ordinal }.forEach { (t, v) -> appendLine("  ${t.name.padEnd(16)} ${v.first}/${v.second}") }
        if (mistakes.isNotEmpty()) appendLine("Fehler:")
        mistakes.forEach { appendLine("  - $it") }
    }

    private fun fmt(d: Double) = ((d * 10).toInt() / 10.0).toString()
}

internal fun evaluate(forms: List<CorpusForm>, analyzer: FormAnalyzer = FormAnalyzer()): Evaluation {
    var total = 0
    var correct = 0
    var functional = 0
    val identifiers = setOf(FieldType.Username, FieldType.Email)
    var intents = 0
    var intentForms = 0
    val mistakes = ArrayList<String>()
    val perType = HashMap<FieldType, Pair<Int, Int>>()
    for (form in forms) {
        val analysis = analyzer.analyze(form.root)
        val detected = analysis.fields.associate { it.id to it.type }
        for ((id, want) in form.expected) {
            val got = detected[id] ?: FieldType.Unknown
            total++
            val ok = got == want
            if (ok || (got in identifiers && want in identifiers)) functional++
            if (ok) correct++ else {
                val signals = analysis.fields.firstOrNull { it.id == id }?.signals?.take(4).orEmpty()
                mistakes += "${form.source} $id: erwartet $want, erkannt $got $signals"
            }
            val (c, n) = perType[want] ?: (0 to 0)
            perType[want] = (c + if (ok) 1 else 0) to (n + 1)
        }
        if (form.intent != null) {
            intentForms++
            if (analysis.intent == form.intent) intents++ else mistakes += "${form.source}: Absicht ${form.intent} erwartet, erkannt ${analysis.intent}"
        }
    }
    return Evaluation(total, correct, intentForms, intents, mistakes, perType, functional)
}

internal val FormIntent.label get() = name
