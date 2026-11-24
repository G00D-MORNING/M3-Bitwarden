package com.framepersecond.m3bitwarden.autofill.corpus

import com.framepersecond.m3bitwarden.autofill.classify.FieldClassifier
import com.framepersecond.m3bitwarden.autofill.classify.FieldFeatures
import com.framepersecond.m3bitwarden.autofill.classify.FormAnalyzer
import com.framepersecond.m3bitwarden.autofill.classify.LearnedModel
import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.model.FieldType
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Trains [LearnedModel] on every labelled form we have and measures the whole system honestly:
 *
 * - Training data: the hand-built [FormCorpus], Chromium's real-world corpus
 *   (`training/chromium.tsv`) and pages crawled with headless Chrome (`training/crawl.tsv`).
 * - 5-fold cross-validation over the real-world forms: each fold is labelled by rules + a model
 *   that never saw those forms. This is the number to quote for unknown websites.
 * - The model's confidence margin is chosen by that cross-validation and stored with the weights.
 *
 * `M3B_TRAIN=1 ./gradlew :autofill-core:jvmTest --tests "*ModelTrainer*"` writes `TrainedWeights.kt`.
 */
class ModelTrainer {

    private data class Example(val features: List<String>, val label: Int)

    private val classes = FieldType.entries
    private val labeller = FormAnalyzer(FieldClassifier(model = null))

    private fun examples(forms: List<CorpusForm>, augment: Boolean, seed: Int = 42): List<Example> {
        val rnd = Random(seed)
        val out = ArrayList<Example>()
        for (form in forms) {
            for ((node, labels) in labeller.labelledInputs(form.root)) {
                val want = form.expected[node.id] ?: continue
                out += Example(FieldFeatures.extract(node, labels), want.ordinal)
                if (augment) variants(node, labels, rnd).forEach { (n, l) -> out += Example(FieldFeatures.extract(n, l), want.ordinal) }
            }
        }
        return out
    }

    /** The same field as other pages would write it: without label, without ids, without autocomplete, with prefixes. */
    private fun variants(node: AutofillNode, labels: List<String>, rnd: Random): List<Pair<AutofillNode, List<String>>> {
        val prefixes = listOf("billing_", "shipping_", "customer_", "user_", "form-", "input_", "checkout[", "data[", "ctl00\$main\$")
        val attrs = node.htmlAttributes
        val out = ArrayList<Pair<AutofillNode, List<String>>>()
        if (attrs["autocomplete"] != null) out += node.copy(htmlAttributes = attrs - "autocomplete") to labels
        if (attrs["label"] != null || labels.isNotEmpty()) out += node.copy(htmlAttributes = attrs - "label" - "autocomplete") to emptyList()
        if (attrs["name"] != null || attrs["id"] != null || node.idEntry != null) out += node.copy(htmlAttributes = attrs - "name" - "id" - "autocomplete", idEntry = null) to labels
        attrs["name"]?.let { name ->
            val p = prefixes[rnd.nextInt(prefixes.size)]
            out += node.copy(htmlAttributes = attrs - "autocomplete" + ("name" to (if (p.endsWith("[")) "$p$name]" else p + name))) to labels
        }
        return out
    }

    /** Averaged multi-class perceptron; weights scaled by 100 and rounded. */
    private fun train(data: List<Example>, epochs: Int = 12): LearnedModel {
        val n = classes.size
        val w = HashMap<String, DoubleArray>()
        val acc = HashMap<String, DoubleArray>()
        var step = 1.0
        val rnd = Random(7)
        repeat(epochs) {
            for (ex in data.shuffled(rnd)) {
                val scores = DoubleArray(n)
                for (f in ex.features) w[f]?.let { for (i in 0 until n) scores[i] += it[i] }
                var pred = 0
                for (i in 1 until n) if (scores[i] > scores[pred]) pred = i
                if (pred != ex.label) {
                    for (f in ex.features) {
                        val wf = w.getOrPut(f) { DoubleArray(n) }
                        val af = acc.getOrPut(f) { DoubleArray(n) }
                        wf[ex.label] += 1.0; af[ex.label] += step
                        wf[pred] -= 1.0; af[pred] -= step
                    }
                }
                step += 1.0
            }
        }
        val averaged = w.mapValues { (f, wf) ->
            val af = acc.getValue(f)
            IntArray(n) { i -> ((wf[i] - af[i] / step) * 100).toInt() }
        }.filterValues { arr -> arr.any { it != 0 } }
        return LearnedModel(averaged)
    }

    private fun withMargin(model: LearnedModel, margin: Int, overrides: Boolean = false): LearnedModel {
        val field = LearnedModel::class.java.getDeclaredField("weights").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return LearnedModel(field.get(model) as Map<String, IntArray>, margin, overrides)
    }

    @Test
    fun trainAndReport() {
        val handmade = FormCorpus.all
        val real = TsvCorpus.load("training/chromium.tsv") + TsvCorpus.load("training/crawl.tsv")
        println("Trainingsdaten: ${handmade.size} eigene + ${real.size} echte Formulare")

        val rulesOnly = evaluate(real, FormAnalyzer(FieldClassifier(model = null)))
        println("Echte Formulare, nur Regeln: ${rulesOnly.correctFields}/${rulesOnly.fields} (${pct(rulesOnly.correctFields, rulesOnly.fields)} %)")

        // 5-fold CV over the Chromium forms (by form): rules + a model that never saw the fold.
        // Crawled pages stay out of tuning: they are the independent check below.
        val chromium = TsvCorpus.load("training/chromium.tsv")
        val crawl = TsvCorpus.load("training/crawl.tsv")
        val folds = 5
        val settings = listOf(100, 200, 300, 500, 800).flatMap { m -> listOf(m to false, m to true) }
        val correct = IntArray(settings.size)
        var total = 0
        val shuffled = chromium.shuffled(Random(3))
        for (k in 0 until folds) {
            val test = shuffled.filterIndexed { i, _ -> i % folds == k }
            val trainForms = handmade + shuffled.filterIndexed { i, _ -> i % folds != k }
            val model = train(examples(trainForms, augment = true))
            settings.forEachIndexed { si, (m, o) ->
                val e = evaluate(test, FormAnalyzer(FieldClassifier(model = withMargin(model, m, o))))
                correct[si] += e.correctFields
                if (si == 0) total += e.fields
            }
        }
        settings.forEachIndexed { i, (m, o) -> println("  Chrome-Formulare, Regeln + Modell (Kreuzvalidierung, Vorsprung $m${if (o) ", überstimmt schwache Regeln" else ""}): ${correct[i]}/$total (${pct(correct[i], total)} %)") }
        val best = settings.indices.maxByOrNull { correct[it] }!!
        val (bestMargin, bestOverrides) = settings[best]
        println("Gewählt: Vorsprung $bestMargin, überstimmen=$bestOverrides")

        // Independent check: crawled pages, model trained without them.
        if (crawl.isNotEmpty()) {
            val m = withMargin(train(examples(handmade + chromium, augment = true)), bestMargin, bestOverrides)
            val r = evaluate(crawl, FormAnalyzer(FieldClassifier(model = null)))
            val c = evaluate(crawl, FormAnalyzer(FieldClassifier(model = m)))
            println("Gecrawlte Seiten (unabhängig), nur Regeln: ${r.correctFields}/${r.fields} (${pct(r.correctFields, r.fields)} %), beim Ausfüllen richtig ${pct(r.functionalCorrect, r.fields)} %")
            println("Gecrawlte Seiten (unabhängig), Regeln + Modell: ${c.correctFields}/${c.fields} (${pct(c.correctFields, c.fields)} %), beim Ausfüllen richtig ${pct(c.functionalCorrect, c.fields)} %")
            c.mistakes.take(25).forEach { println("    - $it") }
        }

        val finalModel = withMargin(train(examples(handmade + real, augment = true)), bestMargin, bestOverrides)
        for ((name, set) in listOf("Trainingskorpus" to handmade, "Prüfkorpus" to HoldoutCorpus.all, "Validierung" to ValidationCorpus.all)) {
            val e = evaluate(set, FormAnalyzer(FieldClassifier(model = finalModel)))
            println("$name mit Endmodell: ${e.correctFields}/${e.fields}")
            e.mistakes.take(5).forEach { println("    - $it") }
        }
        if (System.getenv("M3B_TRAIN") == "1") write(finalModel, bestMargin, bestOverrides)
    }

    private fun encoded(model: LearnedModel): String {
        val field = LearnedModel::class.java.getDeclaredField("weights").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return LearnedModel.encode(field.get(model) as Map<String, IntArray>)
    }

    private fun write(model: LearnedModel, margin: Int, overrides: Boolean) {
        val data = encoded(model)
        // JVM string constants are limited to 64 KB, so the data is split into chunks.
        val chunks = data.chunked(30_000)
        val file = File("src/commonMain/kotlin/com/framepersecond/m3bitwarden/autofill/classify/TrainedWeights.kt")
        file.writeText(buildString {
            appendLine("package com.framepersecond.m3bitwarden.autofill.classify")
            appendLine()
            appendLine("/** Generated by ModelTrainer (autofill-core JVM tests, M3B_TRAIN=1). Do not edit by hand. */")
            appendLine("internal object TrainedWeights {")
            appendLine("    const val MARGIN: Int = $margin")
            appendLine("    const val OVERRIDES: Boolean = $overrides")
            appendLine()
            appendLine("    val DATA: String get() = PARTS.joinToString(\"\")")
            appendLine()
            appendLine("    private val PARTS = arrayOf(")
            chunks.forEach { c -> appendLine("        \"" + c.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$").replace("\n", "\\n").replace("\t", "\\t") + "\",") }
            appendLine("    )")
            appendLine("}")
        })
        println("Gewichte geschrieben: ${data.lines().size} Merkmale, ${data.length / 1024} KB, Vorsprung $margin")
    }

    private fun pct(a: Int, b: Int) = if (b == 0) 0.0 else ((a * 1000.0 / b).toInt() / 10.0)
}
