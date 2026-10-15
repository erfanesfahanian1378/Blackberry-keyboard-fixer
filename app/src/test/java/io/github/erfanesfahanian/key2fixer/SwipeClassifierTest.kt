package io.github.erfanesfahanian.key2fixer

import io.github.erfanesfahanian.key2fixer.gesture.StepAccumulator
import io.github.erfanesfahanian.key2fixer.gesture.SwipeClassifier
import io.github.erfanesfahanian.key2fixer.gesture.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeClassifierTest {
    private fun classifier() = SwipeClassifier(verticalThreshold = 0.10f, horizontalThreshold = 0.14f)

    @Test
    fun upwardSwipeFiresOnceWhenThresholdCrossed() {
        val c = classifier()
        c.down(0.5f, 0.30f, 0)
        assertNull(c.move(0.5f, 0.25f, 20))
        assertEquals(SwipeDirection.UP, c.move(0.51f, 0.19f, 40))
        assertNull("only one swipe per touch", c.move(0.51f, 0.05f, 60))
        assertNull(c.up(0.51f, 0.02f, 80))
    }

    @Test
    fun downwardAndHorizontalSwipes() {
        val c = classifier()
        c.down(0.5f, 0.1f, 0)
        assertEquals(SwipeDirection.DOWN, c.move(0.5f, 0.25f, 50))
        c.down(0.5f, 0.2f, 0)
        assertEquals(SwipeDirection.LEFT, c.move(0.3f, 0.2f, 50))
        c.down(0.2f, 0.2f, 0)
        assertEquals(SwipeDirection.RIGHT, c.move(0.4f, 0.21f, 50))
    }

    @Test
    fun keyPressJitterIsIgnored() {
        val c = classifier()
        c.down(0.4f, 0.2f, 0)
        assertNull(c.move(0.41f, 0.21f, 30))
        assertNull(c.up(0.41f, 0.215f, 90))
    }

    @Test
    fun diagonalWithoutDominantAxisIsIgnored() {
        val c = classifier()
        c.down(0.4f, 0.3f, 0)
        assertNull(c.move(0.55f, 0.15f, 400))
        assertNull(c.up(0.56f, 0.14f, 500))
    }

    @Test
    fun shortFastFlickCountsWithLessTravel() {
        val c = classifier()
        c.down(0.5f, 0.3f, 0)
        assertNull(c.move(0.5f, 0.25f, 40))
        assertEquals(SwipeDirection.UP, c.up(0.5f, 0.23f, 90))
        // The same distance done slowly is not a swipe.
        c.down(0.5f, 0.3f, 0)
        assertNull(c.move(0.5f, 0.25f, 200))
        assertNull(c.up(0.5f, 0.23f, 600))
    }

    @Test
    fun cancelSuppressesTheRestOfTheGesture() {
        val c = classifier()
        c.down(0.5f, 0.3f, 0)
        c.cancel()
        assertNull(c.move(0.5f, 0.0f, 50))
        assertNull(c.up(0.5f, 0.0f, 60))
    }

    @Test
    fun thresholdLevelsScaleAroundTheBase() {
        assertEquals(0.10f, SwipeClassifier.thresholdForLevel(5, 0.10f), 1e-6f)
        assertTrue(SwipeClassifier.thresholdForLevel(1, 0.10f) < SwipeClassifier.thresholdForLevel(10, 0.10f))
        assertEquals(SwipeClassifier.thresholdForLevel(10, 0.1f), SwipeClassifier.thresholdForLevel(42, 0.1f), 1e-6f)
    }

    @Test
    fun stepAccumulatorCarriesRemainders() {
        val s = StepAccumulator(0.05f)
        assertEquals(0, s.add(0.03f))
        assertEquals(1, s.add(0.03f))
        assertEquals(-1, s.add(-0.06f))
        assertEquals(2, s.add(0.11f))
    }
}
