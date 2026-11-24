package com.framepersecond.m3bitwarden.autofill.classify

import com.framepersecond.m3bitwarden.autofill.model.FieldType

/**
 * Linear multi-class model over [FieldFeatures], trained offline (averaged perceptron, see
 * `ModelTrainer` in the JVM tests) on the labelled form corpus. Runs on the device in
 * microseconds and needs no network. The rule-based [FieldClassifier] stays in charge; this
 * model decides only where the rules have no answer.
 */
class LearnedModel(
    private val weights: Map<String, IntArray>,
    val margin: Int = DEFAULT_MARGIN,
    /** Chosen in training: whether the model may overrule weak rule decisions. */
    val overridesWeakRules: Boolean = false,
) {
    val classes: List<FieldType> = FieldType.entries

    fun scores(features: List<String>): IntArray {
        val out = IntArray(classes.size)
        for (f in features) {
            val w = weights[f] ?: continue
            for (i in out.indices) out[i] += w[i]
        }
        return out
    }

    /** Best class and its margin over the runner-up (in weight units). */
    fun predict(features: List<String>): Pair<FieldType, Int> {
        val s = scores(features)
        var best = 0
        var second = -1
        for (i in s.indices) {
            if (s[i] > s[best]) {
                second = best
                best = i
            } else if (i != best && (second < 0 || s[i] > s[second])) {
                second = i
            }
        }
        return classes[best] to (s[best] - (if (second >= 0) s[second] else 0))
    }

    companion object {
        const val DEFAULT_MARGIN = 300

        /** The model shipped with the app. */
        val shipped: LearnedModel by lazy { decode(TrainedWeights.DATA, TrainedWeights.MARGIN, TrainedWeights.OVERRIDES) }

        /**
         * Format: one line per feature, `feature<TAB>Type=weight,Type=weight` with [FieldType] names,
         * so adding a field type never shifts the meaning of stored weights.
         */
        fun decode(data: String, margin: Int = DEFAULT_MARGIN, overrides: Boolean = false): LearnedModel {
            val n = FieldType.entries.size
            val byName = FieldType.entries.associateBy { it.name }
            val map = HashMap<String, IntArray>()
            for (line in data.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val arr = IntArray(n)
                line.substring(tab + 1).split(',').forEach { pair ->
                    val eq = pair.indexOf('=')
                    val type = if (eq > 0) byName[pair.substring(0, eq)] else null
                    if (type != null) arr[type.ordinal] = pair.substring(eq + 1).toInt()
                }
                map[line.substring(0, tab)] = arr
            }
            return LearnedModel(map, margin, overrides)
        }

        fun encode(weights: Map<String, IntArray>): String = buildString {
            val types = FieldType.entries
            weights.entries.sortedBy { it.key }.forEach { (f, w) ->
                val nonZero = w.withIndex().filter { it.value != 0 }
                if (nonZero.isEmpty()) return@forEach
                append(f).append('\t').append(nonZero.joinToString(",") { "${types[it.index].name}=${it.value}" }).append('\n')
            }
        }
    }
}
