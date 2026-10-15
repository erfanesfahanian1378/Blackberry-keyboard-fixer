package io.github.erfanesfahanian.key2fixer

import io.github.erfanesfahanian.key2fixer.ime.TextContext
import io.github.erfanesfahanian.key2fixer.ime.UserDictionary
import io.github.erfanesfahanian.key2fixer.ime.WordPredictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PredictionTest {
    private val words = listOf(
        "the" to 222, "they" to 190, "then" to 185, "there" to 190, "this" to 200, "that" to 205,
        "think" to 170, "thanks" to 160, "hello" to 150, "help" to 160, "helmet" to 90,
        "keyboard" to 120, "key" to 150, "BlackBerry" to 100, "receive" to 130, "with" to 200,
    )

    @Test
    fun completesByFrequency() {
        val p = WordPredictor(words)
        assertEquals(listOf("the", "that", "this"), p.suggest("th", null))
        assertEquals(listOf("help", "hello", "helmet"), p.suggest("hel", null))
    }

    @Test
    fun keepsDictionaryCasing() {
        val p = WordPredictor(words)
        assertEquals("BlackBerry", p.suggest("black", null).first())
    }

    @Test
    fun correctsSmallTypos() {
        val p = WordPredictor(words)
        assertTrue(p.suggest("teh", null).contains("the"))
        assertTrue(p.suggest("recieve", null).contains("receive"))
        assertTrue(p.suggest("wiht", null).contains("with"))
    }

    @Test
    fun numbersAreNotPredicted() {
        val p = WordPredictor(words)
        assertTrue(p.suggest("12", null).isEmpty())
    }

    @Test
    fun learningBoostsWordsAndPredictsNextWord() {
        val user = UserDictionary(null)
        val p = WordPredictor(words, user)
        repeat(5) { user.learn("thanks", null) }
        assertTrue("a few uses bring a word into the bar", p.suggest("th", null).contains("thanks"))
        repeat(25) { user.learn("thanks", null) }
        assertEquals("heavy use beats even the most common word", "thanks", p.suggest("th", null).first())
        user.learn("keyboard", "blackberry")
        user.learn("keyboard", "blackberry")
        assertEquals(listOf("keyboard"), p.suggest("", "blackberry"))
        assertEquals("keyboard", p.suggest("k", "blackberry").first())
    }

    @Test
    fun userOnlyWordsAreSuggested() {
        val user = UserDictionary(null)
        val p = WordPredictor(words, user)
        user.learn("Erfan", null)
        assertFalse("one use may be a typo", p.suggest("erf", null).contains("erfan"))
        user.learn("Erfan", null)
        assertTrue(p.suggest("erf", null).contains("erfan"))
        user.learn("iPhone", null)
        user.learn("iPhone", null)
        assertTrue(p.suggest("iph", null).contains("iPhone"))
    }

    @Test
    fun userDictionaryRoundTrips() {
        val f = File.createTempFile("userdict", ".tsv")
        try {
            val a = UserDictionary(f)
            a.learn("hello", null)
            a.learn("world", "hello")
            a.save()
            val b = UserDictionary(f)
            b.load()
            assertEquals(1, b.count("hello"))
            assertEquals(1, b.bigram("hello", "world"))
            assertFalse(b.isDirty)
        } finally {
            f.delete()
        }
    }

    @Test
    fun oneEditDistance() {
        assertTrue(WordPredictor.withinOneEdit("teh", "the"))
        assertTrue(WordPredictor.withinOneEdit("helo", "hello"))
        assertTrue(WordPredictor.withinOneEdit("hellp", "hello"))
        assertTrue(WordPredictor.withinOneEdit("hhello", "hello"))
        assertTrue(WordPredictor.withinOneEdit("hlelo", "hello"))
        assertFalse(WordPredictor.withinOneEdit("abc", "xyz"))
        assertFalse(WordPredictor.withinOneEdit("ab", "abcd"))
    }

    @Test
    fun textContextReadsWordsAroundCursor() {
        assertEquals("hel", TextContext.currentWordPrefix("I said hel"))
        assertEquals("", TextContext.currentWordPrefix("I said "))
        assertEquals("don't", TextContext.currentWordPrefix("I don't"))
        assertEquals("lo", TextContext.currentWordSuffix("lo world"))
        assertEquals("said", TextContext.previousWord("I said hel"))
        assertEquals("said", TextContext.previousWord("I said "))
        assertNull(TextContext.previousWord("I said. Hel"))
        assertNull(TextContext.previousWord("Hello"))
        assertNull(TextContext.previousWord("call 555 "))
    }

    @Test
    fun caseFollowsWhatWasTyped() {
        assertEquals("The", TextContext.applyCase("the", "Th", false))
        assertEquals("THE", TextContext.applyCase("the", "TH", false))
        assertEquals("the", TextContext.applyCase("the", "th", true))
        assertEquals("The", TextContext.applyCase("the", "", true))
        assertEquals("iPhone", TextContext.applyCase("iPhone", "iph", false))
    }

    @Test
    fun doubleSpaceDetection() {
        assertTrue(TextContext.endsWithWordAndSpace("hello "))
        assertFalse(TextContext.endsWithWordAndSpace("hello. "))
        assertFalse(TextContext.endsWithWordAndSpace("hello  "))
        assertFalse(TextContext.endsWithWordAndSpace(" "))
    }
}
