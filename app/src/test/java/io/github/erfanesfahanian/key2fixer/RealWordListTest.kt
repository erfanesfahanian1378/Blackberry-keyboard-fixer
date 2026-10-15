package io.github.erfanesfahanian.key2fixer

import io.github.erfanesfahanian.key2fixer.ime.WordPredictor
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Sanity checks against the bundled 100k-word list: quality and per-keystroke speed. */
class RealWordListTest {
    companion object {
        private var predictor: WordPredictor? = null

        @BeforeClass
        @JvmStatic
        fun load() {
            val file = listOfNotNull(
                System.getProperty("wordlist"),
                "src/main/assets/dict/en_US.txt",
                "app/src/main/assets/dict/en_US.txt",
            ).map(::File).firstOrNull { it.exists() } ?: return
            predictor = WordPredictor(file.bufferedReader().use { WordPredictor.parseWordList(it) })
        }
    }

    private fun p(): WordPredictor {
        assumeTrue("word list not found", predictor != null)
        return predictor!!
    }

    @Test
    fun commonCompletions() {
        val p = p()
        assertTrue(p.size > 50_000)
        assertTrue(p.suggest("th", null).contains("the"))
        assertTrue(p.suggest("keyb", null).contains("keyboard"))
        assertTrue(p.suggest("Tomor", null).map { it.lowercase() }.contains("tomorrow"))
        assertTrue(p.suggest("dont", null).contains("don't"))
        assertTrue(p.suggest("becuase", null).contains("because"))
    }

    @Test
    fun suggestionsAreFastEnoughPerKeystroke() {
        val p = p()
        val prefixes = listOf("s", "st", "str", "stre", "c", "co", "com", "a", "ab", "x", "qu", "becuase", "teh")
        repeat(3) { prefixes.forEach { p.suggest(it, null) } } // warm up
        val start = System.nanoTime()
        repeat(10) { prefixes.forEach { p.suggest(it, null) } }
        val perCallMs = (System.nanoTime() - start) / 1e6 / (10 * prefixes.size)
        println("average suggest() time: %.3f ms".format(perCallMs))
        assertTrue("suggest() took $perCallMs ms", perCallMs < 25)
    }
}
