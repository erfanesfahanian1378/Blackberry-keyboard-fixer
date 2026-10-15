package io.github.erfanesfahanian.key2fixer.ime

import java.io.File

/**
 * Words and word pairs learned from what you type, stored as a small tab-separated file.
 * Everything is kept on the device; nothing is learned in password or incognito fields.
 */
class UserDictionary(private val file: File?) {
    private class Entry(var count: Int, var display: String)

    private val words = HashMap<String, Entry>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()

    @Volatile
    var isDirty = false
        private set

    @Synchronized
    fun learn(word: String, previous: String?) {
        if (!TextContext.isPredictable(word) || word.length > MAX_WORD_LENGTH) return
        val key = word.lowercase()
        val entry = words.getOrPut(key) { Entry(0, key) }
        entry.count = (entry.count + 1).coerceAtMost(MAX_COUNT)
        // Keep unusual casing such as "iPhone" or "McDonald"; plain sentence-start capitals
        // are not remembered, so "The" at the start of a sentence stays "the".
        if (word.drop(1).any { it.isUpperCase() }) entry.display = word
        if (previous != null && TextContext.isPredictable(previous)) {
            val next = bigrams.getOrPut(previous.lowercase()) { HashMap() }
            next[key] = ((next[key] ?: 0) + 1).coerceAtMost(MAX_COUNT)
            if (next.size > MAX_BIGRAMS_PER_WORD) next.remove(next.minByOrNull { it.value }!!.key)
            if (bigrams.size > MAX_BIGRAM_HEADS) bigrams.remove(bigrams.keys.first())
        }
        if (words.size > MAX_WORDS) prune()
        isDirty = true
    }

    @Synchronized
    fun count(word: String): Int = words[word.lowercase()]?.count ?: 0

    @Synchronized
    fun bigram(previous: String, word: String): Int = bigrams[previous.lowercase()]?.get(word.lowercase()) ?: 0

    /** Learned words that may follow [previous], most frequent first. */
    @Synchronized
    fun next(previous: String, limit: Int): List<String> =
        bigrams[previous.lowercase()].orEmpty().entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { words[it.key]?.display ?: it.key }

    /** Learned words starting with [prefix] (lower-case), with their counts. */
    @Synchronized
    fun withPrefix(prefix: String): List<Pair<String, Int>> =
        words.entries.filter { it.key.startsWith(prefix) }.map { it.value.display to it.value.count }

    @Synchronized
    fun clear() {
        words.clear()
        bigrams.clear()
        isDirty = true
    }

    @Synchronized
    fun size(): Int = words.size

    private fun prune() {
        val drop = words.entries.sortedBy { it.value.count }.take(words.size - MAX_WORDS * 9 / 10)
        for (e in drop) words.remove(e.key)
    }

    @Synchronized
    fun load() {
        val f = file ?: return
        if (!f.exists()) return
        words.clear()
        bigrams.clear()
        f.forEachLine { line ->
            val parts = line.split('\t')
            when {
                parts.size == 4 && parts[0] == "w" ->
                    words[parts[1]] = Entry(parts[3].toIntOrNull() ?: 1, parts[2])
                parts.size == 4 && parts[0] == "b" ->
                    bigrams.getOrPut(parts[1]) { HashMap() }[parts[2]] = parts[3].toIntOrNull() ?: 1
            }
        }
        isDirty = false
    }

    /** Writes the dictionary atomically. Safe to call from a background thread. */
    fun save() {
        val f = file ?: return
        val text = synchronized(this) {
            if (!isDirty) return
            isDirty = false
            buildString {
                for ((key, e) in words) append("w\t").append(key).append('\t').append(e.display).append('\t').append(e.count).append('\n')
                for ((head, next) in bigrams) for ((w, c) in next) {
                    append("b\t").append(head).append('\t').append(w).append('\t').append(c).append('\n')
                }
            }
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        const val MAX_WORDS = 6000
        const val MAX_BIGRAM_HEADS = 4000
        const val MAX_BIGRAMS_PER_WORD = 24
        const val MAX_COUNT = 10_000
        const val MAX_WORD_LENGTH = 40
    }
}
