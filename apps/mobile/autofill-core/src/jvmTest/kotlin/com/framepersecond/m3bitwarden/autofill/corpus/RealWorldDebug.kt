package com.framepersecond.m3bitwarden.autofill.corpus

import com.framepersecond.m3bitwarden.autofill.classify.FormAnalyzer
import com.framepersecond.m3bitwarden.autofill.model.FieldType
import kotlin.test.Test

class RealWorldDebug {
    @Test
    fun samples() {
        val want = System.getenv("M3B_CONF") ?: return
        val (exp, got) = want.split("->")
        val forms = TsvCorpus.load("training/chromium.tsv")
        val analyzer = FormAnalyzer()
        var n = 0
        for (f in forms) {
            val a = analyzer.analyze(f.root)
            val det = a.fields.associate { it.id to it }
            val inputs = analyzer.labelledInputs(f.root).associate { it.first.id to it.first }
            for ((id, e) in f.expected) {
                val d = det[id]
                val g = d?.type ?: FieldType.Unknown
                if (e.name == exp && g.name == got && n++ < 14) {
                    val node = inputs[id]!!
                    println("${f.source.takeLast(30)} | ${node.htmlAttributes} | opts=${node.options.take(5)} | ${d?.signals?.take(5)}")
                }
            }
        }
    }
}
