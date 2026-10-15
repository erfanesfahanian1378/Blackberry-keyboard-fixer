package io.github.erfanesfahanian.key2fixer.ime

/** Helpers for reading the word around the cursor out of plain text. */
object TextContext {
    private val EDGE_MARKS = charArrayOf('\'', '’', '-')

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    private fun isSentenceEnd(c: Char): Boolean = c == '.' || c == '!' || c == '?' || c == '\n'

    /**
     * The fragment of the word that ends at the cursor, without leading apostrophes or hyphens.
     * Returns "" when the cursor follows a space or punctuation.
     */
    fun currentWordPrefix(before: CharSequence?): String {
        if (before.isNullOrEmpty()) return ""
        var start = before.length
        while (start > 0 && isWordChar(before[start - 1])) start--
        return before.subSequence(start, before.length).toString().trimStart(*EDGE_MARKS)
    }

    /** The rest of the word when the cursor sits in the middle of it. */
    fun currentWordSuffix(after: CharSequence?): String {
        if (after.isNullOrEmpty()) return ""
        var end = 0
        while (end < after.length && isWordChar(after[end])) end++
        return after.subSequence(0, end).toString().trimEnd(*EDGE_MARKS)
    }

    /**
     * The complete word before the current fragment, lower-cased, or null if there is none in
     * the same sentence (used for next-word predictions).
     */
    fun previousWord(before: CharSequence?): String? {
        if (before.isNullOrEmpty()) return null
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        // Only plain spacing may separate the two words; punctuation breaks the phrase.
        var gap = 0
        while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\t')) {
            i--
            gap++
        }
        if (gap == 0 || i == 0 || isSentenceEnd(before[i - 1]) || !isWordChar(before[i - 1])) return null
        val end = i
        while (i > 0 && isWordChar(before[i - 1])) i--
        val word = before.subSequence(i, end).toString().trim(*EDGE_MARKS)
        return word.takeIf { isPredictable(it) }?.lowercase()
    }

    /** Numbers, single symbols and mixed alphanumerics are not completed. */
    fun isPredictable(word: String): Boolean =
        word.isNotEmpty() && word.none { it.isDigit() } && word.any { it.isLetter() }

    /** True if the cursor directly follows a single space after a word (for double-space). */
    fun endsWithWordAndSpace(before: CharSequence?): Boolean {
        if (before == null || before.length < 2) return false
        val last = before[before.length - 1]
        val prev = before[before.length - 2]
        return last == ' ' && (prev.isLetterOrDigit() || prev == '\'' || prev == '’' || prev == ')' || prev == '"')
    }

    /**
     * Matches the capitalisation of [typed] onto [suggestion]: "Th" -> "The", "TH" -> "THE".
     * An empty [typed] is capitalised only when [capitalizeIfEmpty] is set (sentence start).
     */
    fun applyCase(suggestion: String, typed: String, capitalizeIfEmpty: Boolean): String {
        if (suggestion.isEmpty()) return suggestion
        if (typed.isEmpty()) return if (capitalizeIfEmpty) capitalizeFirst(suggestion) else suggestion
        val letters = typed.filter { it.isLetter() }
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return suggestion.uppercase()
        if (typed.first().isUpperCase()) return capitalizeFirst(suggestion)
        return suggestion
    }

    private fun capitalizeFirst(s: String): String =
        s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
