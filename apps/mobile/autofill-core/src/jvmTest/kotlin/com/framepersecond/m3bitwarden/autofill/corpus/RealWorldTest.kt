package com.framepersecond.m3bitwarden.autofill.corpus

import kotlin.test.Test

/** Detection on real-world forms (Chromium corpus, crawled pages), with the most common errors. */
class RealWorldTest {
    @Test
    fun report() {
        for (name in listOf("chromium", "crawl")) {
            val forms = TsvCorpus.load("training/$name.tsv")
            if (forms.isEmpty()) continue
            val e = evaluate(forms)
            println("== $name: ${forms.size} Formulare ==")
            println(e.report().lines().takeWhile { !it.startsWith("Fehler") }.joinToString("\n"))
            // Confusions grouped, most frequent first.
            val confusions = e.mistakes.mapNotNull { Regex("erwartet (\\w+), erkannt (\\w+)").find(it)?.let { m -> m.groupValues[1] + " -> " + m.groupValues[2] } }
            confusions.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(25).forEach { println("  ${it.value}x ${it.key}") }
        }
    }
}
