package io.github.erfanesfahanian.key2fixer.gesture

import android.view.InputDevice
import android.view.MotionEvent

/**
 * Converts raw keyboard-touchpad coordinates into "pad widths" (see [SwipeClassifier]).
 *
 * The KEY2's capacitive keyboard (input device "touch_keypad" on LineageOS) reports absolute
 * positions with SOURCE_TOUCHPAD. The axis ranges come from the input device; if a kernel
 * does not report them, the largest coordinates seen so far are used instead.
 */
class TouchpadGeometry {
    private var deviceId = Int.MIN_VALUE
    private var minX = 0f
    private var widthX = DEFAULT_WIDTH
    private var minY = 0f
    private var heightY = DEFAULT_HEIGHT
    private var learnedMaxX = 0f
    private var learnedMaxY = 0f
    private var fromDevice = false

    fun update(event: MotionEvent) {
        if (event.deviceId != deviceId) {
            deviceId = event.deviceId
            val device = event.device
            val rx = device?.getMotionRange(MotionEvent.AXIS_X, event.source)
            val ry = device?.getMotionRange(MotionEvent.AXIS_Y, event.source)
            fromDevice = rx != null && rx.range > 1f
            if (rx != null && rx.range > 1f) {
                minX = rx.min
                widthX = rx.range
            }
            if (ry != null && ry.range > 1f) {
                minY = ry.min
                heightY = ry.range
            }
        }
        if (!fromDevice) {
            for (i in 0 until event.pointerCount) {
                learnedMaxX = maxOf(learnedMaxX, event.getX(i))
                learnedMaxY = maxOf(learnedMaxY, event.getY(i))
            }
            widthX = maxOf(DEFAULT_WIDTH, learnedMaxX)
            heightY = maxOf(DEFAULT_HEIGHT, learnedMaxY)
        }
    }

    /** X in pad widths: 0 at the left edge, 1 at the right edge. */
    fun x(rawX: Float): Float = (rawX - minX) / widthX

    /** Y in pad widths (same unit as X), 0 at the top edge. */
    fun y(rawY: Float): Float = (rawY - minY) / widthX

    /** Pad height in pad widths. */
    val aspect: Float get() = heightY / widthX

    /** Horizontal position as a fraction of the width, clamped to 0..1. */
    fun xFraction(rawX: Float): Float = x(rawX).coerceIn(0f, 1f)

    fun describe(): String =
        "x=[%.0f..%.0f] y=[%.0f..%.0f]%s".format(minX, minX + widthX, minY, minY + heightY, if (fromDevice) "" else " (learned)")

    companion object {
        // The ROM maps touchpad X with "x / 1080 * screenWidth", so 1080 is the expected width.
        private const val DEFAULT_WIDTH = 1080f
        private const val DEFAULT_HEIGHT = 400f

        fun isKeyboardTouch(event: MotionEvent): Boolean =
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD) && event.device?.isExternal != true
    }
}
