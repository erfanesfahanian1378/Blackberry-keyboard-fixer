package io.github.erfanesfahanian.key2fixer.ime

import java.io.BufferedReader
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Word completion and correction over a frequency word list, boosted by what the user types.
 *
 * Word list frequencies use the AOSP LatinIME scale (0..255, roughly logarithmic), so they can
 * be added to the small learning boosts directly.
 */
class WordPredictor(entries: List<Pair<String, Int>>, var user: UserDictionary? = null) {
    private val keys: Array<String>
    private val display: Array<String>
    private val freq: IntArray
    private val byLength: Array<IntArray>

    init {
        val sorted = entries
            .filter { it.first.isNotBlank() }
            .map { Triple(it.first.lowercase(), it.first, it.second) }
            .sortedWith(compareBy({ it.first }, { -it.third }))
            .distinctBy { it.first }
        keys = Array(sorted.size) { sorted[it].first }
        display = Array(sorted.size) { sorted[it].second }
        freq = IntArray(sorted.size) { sorted[it].third }
        val maxLen = (keys.maxOfOrNull { it.length } ?: 0) + 2
        val buckets = Array(maxLen) { ArrayList<Int>() }
        keys.forEachIndexed { i, k -> buckets[k.length].add(i) }
        byLength = Array(maxLen) { buckets[it].toIntArray() }
    }

    val size: Int get() = keys.size

    fun contains(word: String): Boolean = indexOf(word.lowercase()) >= 0

    private fun indexOf(key: String): Int {
        val i = lowerBound(key)
        return if (i < keys.size && keys[i] == key) i else -1
    }

    /** First index whose key is >= [key]. */
    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Up to [limit] suggestions for the fragment [typed], best first. With an empty fragment
     * the suggestions are predicted next words for [previous].
     */
    fun suggest(typed: String, previous: String?, limit: Int = 3): List<String> {
        if (typed.isEmpty()) return previous?.let { user?.next(it, limit) }.orEmpty()
        if (!TextContext.isPredictable(typed)) return emptyList()
        val prefix = typed.lowercase()
        val best = TopK(limit)

        // Completions from the word list (a contiguous range of the sorted keys).
        var i = lowerBound(prefix)
        var completions = 0
        while (i < keys.size && keys[i].startsWith(prefix)) {
            best.offer(keys[i], display[i], score(i, keys[i], prefix, previous))
            i++
            completions++
        }
        // Words only the user has typed (names, slang). A single use may have been a typo.
        user?.withPrefix(prefix)?.forEach { (word, count) ->
            val k = word.lowercase()
            if (count >= MIN_USER_ONLY_COUNT && indexOf(k) < 0) {
                completions++
                best.offer(k, word, USER_ONLY_BASE + boost(count, USER_WEIGHT) + bigramBoost(previous, k) + exactBonus(k, prefix))
            }
        }
        // Spelling corrections when the fragment completes to little or nothing.
        if (prefix.length >= 3 && completions < limit + 2) {
            for (len in prefix.length - 1..prefix.length + 1) {
                if (len <= 0 || len >= byLength.size) continue
                for (idx in byLength[len]) {
                    val k = keys[idx]
                    if (k.startsWith(prefix)) continue
                    if (withinOneEdit(prefix, k)) best.offer(k, display[idx], score(idx, k, prefix, previous) - CORRECTION_PENALTY)
                }
            }
        }
        return best.result()
    }

    /** Keeps the [limit] best-scoring distinct words without sorting every candidate. */
    private class TopK(private val limit: Int) {
        private val keys = ArrayList<String>(limit + 1)
        private val words = ArrayList<String>(limit + 1)
        private val scores = ArrayList<Double>(limit + 1)

        fun offer(key: String, word: String, score: Double) {
            val existing = keys.indexOf(key)
            if (existing >= 0) {
                if (scores[existing] < score) {
                    words[existing] = word
                    scores[existing] = score
                }
                return
            }
            if (keys.size < limit) {
                keys.add(key)
                words.add(word)
                scores.add(score)
                return
            }
            var worst = 0
            for (j in 1 until scores.size) if (scores[j] < scores[worst]) worst = j
            if (limit > 0 && score > scores[worst]) {
                keys[worst] = key
                words[worst] = word
                scores[worst] = score
            }
        }

        fun result(): List<String> = words.indices.sortedByDescending { scores[it] }.map { words[it] }
    }

    private fun score(index: Int, key: String, prefix: String, previous: String?): Double {
        val learned = user?.count(key) ?: 0
        return freq[index] + boost(learned, USER_WEIGHT) + bigramBoost(previous, key) + exactBonus(key, prefix)
    }

    private fun exactBonus(key: String, prefix: String): Double = if (key == prefix) EXACT_BONUS else 0.0

    private fun bigramBoost(previous: String?, key: String): Double =
        if (previous == null) 0.0 else boost(user?.bigram(previous, key) ?: 0, BIGRAM_WEIGHT)

    private fun boost(count: Int, weight: Double): Double = if (count <= 0) 0.0 else min(weight * 3.0, weight * ln(1.0 + count))

    companion object {
        private const val USER_WEIGHT = 25.0
        private const val BIGRAM_WEIGHT = 28.0
        private const val USER_ONLY_BASE = 120.0
        private const val MIN_USER_ONLY_COUNT = 2
        private const val EXACT_BONUS = 12.0
        private const val CORRECTION_PENALTY = 45.0

        /** True if [a] and [b] differ by one insertion, deletion, substitution or swap. */
        fun withinOneEdit(a: String, b: String): Boolean {
            if (a == b) return true
            val la = a.length
            val lb = b.length
            if (abs(la - lb) > 1) return false
            var i = 0
            while (i < la && i < lb && a[i] == b[i]) i++
            if (la == lb) {
                if (a.regionMatches(i + 1, b, i + 1, la - i - 1)) return true // substitution
                // adjacent transposition: "teh" vs "the"
                return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] && a.regionMatches(i + 2, b, i + 2, la - i - 2)
            }
            return if (la > lb) a.regionMatches(i + 1, b, i, lb - i) else b.regionMatches(i + 1, a, i, la - i)
        }

        /**
         * Parses the bundled word list: one "word<TAB>frequency" pair per line; lines starting
         * with '#' are comments.
         */
        fun parseWordList(reader: BufferedReader): List<Pair<String, Int>> {
            val out = ArrayList<Pair<String, Int>>(120_000)
            reader.forEachLine { line ->
                if (line.isEmpty() || line[0] == '#') return@forEachLine
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val f = line.substring(tab + 1).trim().toIntOrNull() ?: return@forEachLine
                out.add(line.substring(0, tab) to f)
            }
            return out
        }
    }
}
