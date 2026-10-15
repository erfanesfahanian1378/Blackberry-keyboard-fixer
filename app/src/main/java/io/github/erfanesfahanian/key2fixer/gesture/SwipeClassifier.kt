package io.github.erfanesfahanian.key2fixer.gesture

import kotlin.math.abs

/** Direction the finger moved on the keyboard surface. */
enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Recognises at most one swipe per finger-down on the KEY2's capacitive keyboard.
 *
 * Coordinates are expected in "pad widths": both axes divided by the touchpad's X range, so a
 * movement of 0.1 means one tenth of the keyboard width on either axis. Keeping one unit for
 * both axes means the dominance check compares physical distances, as the ROM's own scroll
 * handler does with raw units.
 *
 * Pressing keys also produces small touches on the keyboard surface, so a swipe is only
 * reported once the finger travels a clear distance along a single dominant axis.
 */
class SwipeClassifier(
    /** Minimum vertical travel, in pad widths. */
    var verticalThreshold: Float = 0.10f,
    /** Minimum horizontal travel, in pad widths. */
    var horizontalThreshold: Float = 0.14f,
    /** How much larger the main axis must be than the other one. */
    var dominance: Float = 1.3f,
    /** A gesture shorter than this may be recognised as a flick with less travel. */
    var flickMaxMillis: Long = 200,
    /** Fraction of the threshold a short flick needs. */
    var flickFactor: Float = 0.6f,
) {
    var isActive = false
        private set
    var hasFired = false
        private set
    var startX = 0f
        private set
    var startY = 0f
        private set
    var startTime = 0L
        private set
    var lastX = 0f
        private set
    var lastY = 0f
        private set
    var lastTime = 0L
        private set

    val totalDx: Float get() = lastX - startX
    val totalDy: Float get() = lastY - startY
    val durationMillis: Long get() = lastTime - startTime

    fun down(x: Float, y: Float, time: Long) {
        isActive = true
        hasFired = false
        startX = x
        startY = y
        startTime = time
        lastX = x
        lastY = y
        lastTime = time
    }

    /** Returns a direction the first time the finger has travelled far enough. */
    fun move(x: Float, y: Float, time: Long): SwipeDirection? {
        if (!isActive) return null
        lastX = x
        lastY = y
        lastTime = time
        if (hasFired) return null
        val dir = classify(totalDx, totalDy, 1f) ?: return null
        hasFired = true
        return dir
    }

    /**
     * Ends the gesture. Returns a direction if the final position crosses the threshold, or if
     * a short, fast flick covered most of it, and nothing has been reported yet.
     */
    fun up(x: Float, y: Float, time: Long): SwipeDirection? {
        if (!isActive) return null
        val crossed = move(x, y, time)
        isActive = false
        if (crossed != null || hasFired) return crossed
        if (durationMillis <= flickMaxMillis) {
            val flick = classify(totalDx, totalDy, flickFactor)
            if (flick != null) {
                hasFired = true
                return flick
            }
        }
        return null
    }

    /** Abandons the gesture, e.g. when a key is pressed or a second finger lands. */
    fun cancel() {
        isActive = false
        hasFired = true
    }

    fun classify(dx: Float, dy: Float, scale: Float): SwipeDirection? {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ay >= verticalThreshold * scale && ay >= ax * dominance) {
            return if (dy < 0) SwipeDirection.UP else SwipeDirection.DOWN
        }
        if (ax >= horizontalThreshold * scale && ax >= ay * dominance) {
            return if (dx < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT
        }
        return null
    }

    companion object {
        /** Maps a 1..10 "swipe distance" setting to a threshold in pad widths. */
        fun thresholdForLevel(level: Int, base: Float): Float {
            val clamped = level.coerceIn(1, 10)
            // Level 5 returns `base`; each step is 15% of base.
            return base * (1f + (clamped - 5) * 0.15f)
        }
    }
}

/**
 * Splits horizontal finger travel into whole cursor steps, carrying the remainder over so slow
 * drags still move the cursor smoothly.
 */
class StepAccumulator(var stepSize: Float) {
    private var carry = 0f

    fun reset() {
        carry = 0f
    }

    /** Adds [delta] and returns the number of whole steps (negative = backwards). */
    fun add(delta: Float): Int {
        if (stepSize <= 0f) return 0
        carry += delta
        val steps = (carry / stepSize).toInt()
        carry -= steps * stepSize
        return steps
    }
}
