package io.github.erfanesfahanian.key2fixer.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import io.github.erfanesfahanian.key2fixer.gesture.SwipeDirection
import kotlin.math.abs

/**
 * Plays touchscreen swipes through the accessibility service. Unlike the mouse-wheel events the
 * ROM generates, these are real touch gestures, so RecyclerView/ViewPager snapping, flinging and
 * "next reel" logic in apps all work exactly as with a finger.
 */
class GestureInjector(private val service: AccessibilityService) {
    private var lastDispatch = 0L
    private var holdStroke: GestureDescription.StrokeDescription? = null
    private var holdX = 0f
    private var holdY = 0f

    private fun screenSize(): Point {
        val wm = service.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getRealSize(it) }
        }
    }

    /**
     * A quick swipe that moves a full-screen pager (Reels, Shorts, TikTok) by exactly one item.
     * [direction] is the direction the content should be pushed, like a finger on the screen:
     * UP shows the next video.
     */
    fun flick(direction: SwipeDirection): Boolean {
        val s = screenSize()
        val w = s.x.toFloat()
        val h = s.y.toFloat()
        val (from, to) = when (direction) {
            SwipeDirection.UP -> floatArrayOf(w * 0.5f, h * 0.70f) to floatArrayOf(w * 0.5f, h * 0.28f)
            SwipeDirection.DOWN -> floatArrayOf(w * 0.5f, h * 0.30f) to floatArrayOf(w * 0.5f, h * 0.72f)
            SwipeDirection.LEFT -> floatArrayOf(w * 0.80f, h * 0.5f) to floatArrayOf(w * 0.20f, h * 0.5f)
            SwipeDirection.RIGHT -> floatArrayOf(w * 0.20f, h * 0.5f) to floatArrayOf(w * 0.80f, h * 0.5f)
        }
        return dispatch(from[0], from[1], to[0], to[1], FLICK_MILLIS)
    }

    /**
     * Replays a keyboard swipe as a finger drag. [dx]/[dy] are the finger travel in pad widths;
     * a full-height swipe on the keyboard (about 0.4) becomes roughly two thirds of the screen.
     */
    fun drag(dx: Float, dy: Float, durationMillis: Long): Boolean {
        val s = screenSize()
        val w = s.x.toFloat()
        val h = s.y.toFloat()
        val vertical = abs(dy) >= abs(dx)
        val length = if (vertical) (abs(dy) * DRAG_GAIN * h).coerceIn(h * 0.06f, h * 0.70f)
        else (abs(dx) * DRAG_GAIN * w).coerceIn(w * 0.10f, w * 0.75f)
        val duration = durationMillis.coerceIn(MIN_DRAG_MILLIS, MAX_DRAG_MILLIS)
        return if (vertical) {
            val sign = if (dy < 0) -1f else 1f
            val startY = h / 2f - sign * length / 2f
            dispatch(w / 2f, startY, w / 2f, startY + sign * length, duration)
        } else {
            val sign = if (dx < 0) -1f else 1f
            val startX = w / 2f - sign * length / 2f
            dispatch(startX, h / 2f, startX + sign * length, h / 2f, duration)
        }
    }

    /** True if a gesture was dispatched very recently (avoid cancelling it with a new one). */
    fun isBusy(): Boolean = SystemClock.uptimeMillis() - lastDispatch < BUSY_MILLIS

    /** A single tap at a point given as fractions of the screen (pause / play a video). */
    fun tap(fx: Float, fy: Float): Boolean {
        val s = screenSize()
        val stroke = GestureDescription.StrokeDescription(dot(s.x * fx, s.y * fy), 0, TAP_MILLIS)
        return send(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** Two quick taps at the same point (likes a Reel, Short or TikTok). */
    fun doubleTap(fx: Float, fy: Float): Boolean {
        val s = screenSize()
        val x = s.x * fx
        val y = s.y * fy
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(dot(x, y), 0, TAP_MILLIS))
            .addStroke(GestureDescription.StrokeDescription(dot(x, y), TAP_MILLIS + DOUBLE_TAP_GAP_MILLIS, TAP_MILLIS))
            .build()
        return send(gesture)
    }

    /**
     * Puts a finger down and keeps it there until [endHold] (2x speed while holding in Reels,
     * Shorts and TikTok). The stroke "will continue", so Android leaves the pointer down.
     */
    fun beginHold(fx: Float, fy: Float): Boolean {
        val s = screenSize()
        holdX = s.x * fx
        holdY = s.y * fy
        val stroke = GestureDescription.StrokeDescription(dot(holdX, holdY), 0, HOLD_SEGMENT_MILLIS, true)
        val sent = send(GestureDescription.Builder().addStroke(stroke).build())
        holdStroke = if (sent) stroke else null
        return sent
    }

    /** Lifts the finger put down by [beginHold]. */
    fun endHold() {
        val previous = holdStroke ?: return
        holdStroke = null
        val path = Path().apply {
            moveTo(holdX + 1f, holdY) // continue exactly where the held stroke ended
            lineTo(holdX, holdY)
        }
        val end = previous.continueStroke(path, 0, TAP_MILLIS, false)
        lastDispatch = SystemClock.uptimeMillis()
        service.dispatchGesture(GestureDescription.Builder().addStroke(end).build(), null, null)
    }

    /** A 1-pixel path: Android rejects empty paths, and 1px is far below any touch slop. */
    private fun dot(x: Float, y: Float) = Path().apply {
        moveTo(x, y)
        lineTo(x + 1f, y)
    }

    private fun dispatch(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return send(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build())
    }

    /** Any new gesture cancels a finger still held down by [beginHold]. */
    private fun send(gesture: GestureDescription): Boolean {
        holdStroke = null
        lastDispatch = SystemClock.uptimeMillis()
        return service.dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val FLICK_MILLIS = 150L
        private const val MIN_DRAG_MILLIS = 60L
        private const val MAX_DRAG_MILLIS = 450L
        private const val BUSY_MILLIS = 220L
        private const val DRAG_GAIN = 1.6f
        private const val TAP_MILLIS = 40L
        private const val DOUBLE_TAP_GAP_MILLIS = 90L
        private const val HOLD_SEGMENT_MILLIS = 300L
    }
}
