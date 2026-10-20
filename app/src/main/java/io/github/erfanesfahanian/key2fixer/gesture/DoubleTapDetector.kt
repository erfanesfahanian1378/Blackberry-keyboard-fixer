package io.github.erfanesfahanian.key2fixer.gesture

/**
 * Recognises two light taps on the keyboard surface (touches without a key press).
 * Pressing a key also touches the surface, so the caller only reports touches during which no
 * key went down, and taps right after typing are ignored.
 */
class DoubleTapDetector(
    /** A tap is shorter than this. */
    private val maxTapMillis: Long = 220,
    /** ...and moves less than this, in pad widths. */
    private val maxTravel: Float = 0.03f,
    /** The second tap must start within this time after the first one ended. */
    private val maxGapMillis: Long = 350,
    /** Taps this soon after a key press are ignored. */
    private val quietAfterKeyMillis: Long = 400,
) {
    private var lastTapUp = NONE

    /**
     * Reports a finished touch. Returns true when it completes a double tap.
     * [downTime]/[upTime] in ms, [travel] in pad widths, [lastKeyTime] = last key press time.
     */
    fun onTouch(downTime: Long, upTime: Long, travel: Float, lastKeyTime: Long): Boolean {
        val isTap = upTime - downTime <= maxTapMillis && travel <= maxTravel &&
            downTime - lastKeyTime >= quietAfterKeyMillis
        if (!isTap) {
            lastTapUp = NONE
            return false
        }
        if (lastTapUp != NONE && downTime - lastTapUp <= maxGapMillis) {
            lastTapUp = NONE
            return true
        }
        lastTapUp = upTime
        return false
    }

    fun reset() {
        lastTapUp = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE / 2
    }
}
