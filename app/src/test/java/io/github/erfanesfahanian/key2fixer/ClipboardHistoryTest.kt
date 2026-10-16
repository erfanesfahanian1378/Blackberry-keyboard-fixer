package io.github.erfanesfahanian.key2fixer

import io.github.erfanesfahanian.key2fixer.ime.ClipboardHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClipboardHistoryTest {
    private fun texts(h: ClipboardHistory) = h.items().map { it.text }

    @Test
    fun newestFirstAndRecopyMovesToTop() {
        val h = ClipboardHistory(null)
        h.add("one", 1)
        h.add("two", 2)
        h.add("three", 3)
        assertEquals(listOf("three", "two", "one"), texts(h))
        assertTrue(h.add("one", 4))
        assertEquals(listOf("one", "three", "two"), texts(h))
        assertFalse("copying the newest clip again changes nothing", h.add("one", 5))
    }

    @Test
    fun blankAndHugeTextsAreIgnored() {
        val h = ClipboardHistory(null)
        assertFalse(h.add("   ", 1))
        assertFalse(h.add("x".repeat(ClipboardHistory.MAX_CHARS + 1), 2))
        assertEquals(0, h.size())
    }

    @Test
    fun pinnedClipsComeFirstAndSurviveTrimAndClear() {
        val h = ClipboardHistory(null, maxRecent = 3)
        h.add("keep me", 1)
        h.togglePin("keep me")
        for (i in 2..10) h.add("clip $i", i.toLong())
        assertEquals(listOf("keep me", "clip 10", "clip 9", "clip 8"), texts(h))
        h.clearUnpinned()
        assertEquals(listOf("keep me"), texts(h))
        h.togglePin("keep me")
        h.clearUnpinned()
        assertEquals(0, h.size())
    }

    @Test
    fun recopyingAPinnedClipKeepsItPinned() {
        val h = ClipboardHistory(null)
        h.add("pinned", 1)
        h.togglePin("pinned")
        h.add("other", 2)
        h.add("pinned", 3)
        assertTrue(h.items().first { it.text == "pinned" }.pinned)
    }

    @Test
    fun removeAndClearAll() {
        val h = ClipboardHistory(null)
        h.add("a", 1)
        h.add("b", 2)
        h.remove("a")
        assertEquals(listOf("b"), texts(h))
        h.togglePin("b")
        h.clearAll()
        assertEquals(0, h.size())
    }

    @Test
    fun roundTripsTabsNewlinesAndUnicode() {
        val f = File.createTempFile("clips", ".tsv")
        try {
            val a = ClipboardHistory(f)
            a.add("line 1\nline 2\twith tab", 10)
            a.add("سلام 👋", 20)
            a.togglePin("سلام 👋")
            a.save()
            val b = ClipboardHistory(f)
            b.load()
            assertEquals(listOf("سلام 👋", "line 1\nline 2\twith tab"), texts(b))
            assertTrue(b.items()[0].pinned)
            assertEquals(10L, b.items()[1].time)
            assertFalse(b.isDirty)
        } finally {
            f.delete()
        }
    }
}
