package io.github.erfanesfahanian.key2fixer

import io.github.erfanesfahanian.key2fixer.gesture.DoubleTapDetector
import io.github.erfanesfahanian.key2fixer.keys.KeyCodes
import io.github.erfanesfahanian.key2fixer.keys.VideoAction
import io.github.erfanesfahanian.key2fixer.keys.VideoKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoShortcutsTest {
    @Test
    fun actionCodesRoundTrip() {
        for (a in VideoAction.entries) assertEquals(a, VideoAction.fromCode(a.code))
        assertEquals(VideoAction.NONE, VideoAction.fromCode(null))
        assertEquals(VideoAction.NONE, VideoAction.fromCode("nope"))
    }

    @Test
    fun keySlotsAndDefaults() {
        assertEquals("P", VideoKeys.slotFor(KeyCodes.A + 15))
        assertEquals(VideoKeys.SPACE, VideoKeys.slotFor(KeyCodes.SPACE))
        assertNull(VideoKeys.slotFor(KeyCodes.SHIFT_RIGHT))
        assertEquals(27, VideoKeys.SLOTS.size)
        assertEquals(VideoAction.SPEED_2X, VideoKeys.DEFAULTS["P"])
        assertEquals(VideoAction.COMMENTS, VideoKeys.DEFAULTS["C"])
        assertEquals(VideoAction.REPOST, VideoKeys.DEFAULTS["R"])
        assertEquals(VideoAction.LIKE, VideoKeys.DEFAULT_DOUBLE_TAP)
        assertTrue(VideoAction.COMMENTS.pressesButton)
        assertFalse(VideoAction.SPEED_2X.pressesButton)
    }

    @Test
    fun twoQuickLightTapsAreADoubleTap() {
        val d = DoubleTapDetector()
        assertFalse(d.onTouch(1000, 1080, 0.01f, lastKeyTime = 0))
        assertTrue(d.onTouch(1250, 1320, 0.01f, lastKeyTime = 0))
        // A third tap starts a new sequence.
        assertFalse(d.onTouch(1400, 1450, 0.0f, lastKeyTime = 0))
    }

    @Test
    fun slowTapsLongTouchesAndSwipesAreNot() {
        val d = DoubleTapDetector()
        assertFalse(d.onTouch(1000, 1080, 0.01f, 0))
        assertFalse("second tap too late", d.onTouch(1600, 1650, 0.01f, 0))
        d.reset()
        assertFalse(d.onTouch(1000, 1500, 0.01f, 0))
        assertFalse("first touch was a hold", d.onTouch(1600, 1650, 0.01f, 0))
        d.reset()
        assertFalse(d.onTouch(1000, 1080, 0.2f, 0))
        assertFalse("first touch was a swipe", d.onTouch(1200, 1250, 0.01f, 0))
    }

    @Test
    fun tapsRightAfterTypingAreIgnored() {
        val d = DoubleTapDetector()
        assertFalse(d.onTouch(1000, 1080, 0.01f, lastKeyTime = 900))
        assertFalse(d.onTouch(1250, 1320, 0.01f, lastKeyTime = 900))
    }
}
