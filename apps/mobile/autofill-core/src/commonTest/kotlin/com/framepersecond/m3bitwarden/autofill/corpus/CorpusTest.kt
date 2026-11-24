package com.framepersecond.m3bitwarden.autofill.corpus

import kotlin.test.Test
import kotlin.test.assertTrue

/** Detection quality on all labelled forms; fails if a change makes it worse. */
class CorpusTest {
    private fun check(name: String, set: List<CorpusForm>, minFields: Double, minIntents: Double) {
        val e = evaluate(set)
        println("== $name ==")
        println(e.report())
        assertTrue(e.fieldAccuracy >= minFields, "$name: Felder ${e.fieldAccuracy} % < $minFields %\n${e.mistakes.joinToString("\n")}")
        assertTrue(e.intentAccuracy >= minIntents, "$name: Absicht ${e.intentAccuracy} % < $minIntents %\n${e.mistakes.joinToString("\n")}")
    }

    @Test
    fun trainingCorpus() = check("Trainingskorpus", FormCorpus.all, 99.0, 98.0)

    @Test
    fun holdoutCorpus() = check("Prüfkorpus", HoldoutCorpus.all, 98.0, 96.0)

    @Test
    fun validationCorpus() = check("Validierung", ValidationCorpus.all, 97.0, 95.0)
}
