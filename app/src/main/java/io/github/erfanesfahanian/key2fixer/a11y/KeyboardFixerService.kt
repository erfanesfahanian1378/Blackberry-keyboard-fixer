package io.github.erfanesfahanian.key2fixer.a11y

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import io.github.erfanesfahanian.key2fixer.R
import io.github.erfanesfahanian.key2fixer.gesture.SwipeClassifier
import io.github.erfanesfahanian.key2fixer.gesture.SwipeDirection
import io.github.erfanesfahanian.key2fixer.gesture.TouchpadGeometry
import io.github.erfanesfahanian.key2fixer.keys.ActionRunner
import io.github.erfanesfahanian.key2fixer.keys.KeyAction
import io.github.erfanesfahanian.key2fixer.keys.KeyConfig
import io.github.erfanesfahanian.key2fixer.keys.KeyInput
import io.github.erfanesfahanian.key2fixer.keys.KeyRouter
import io.github.erfanesfahanian.key2fixer.util.Diagnostics
import io.github.erfanesfahanian.key2fixer.util.HandlerScheduler
import io.github.erfanesfahanian.key2fixer.util.Prefs
import io.github.erfanesfahanian.key2fixer.util.ReelsStyle
import kotlin.math.abs

/**
 * The always-on part of the fixer:
 *
 * 1. **Reels/Shorts paging.** On this ROM the keyboard touch surface is turned into mouse-wheel
 *    scroll events inside each app (ViewRootImpl.SyntheticTouchKeypadHandler). Wheel scrolling
 *    never triggers the snap-to-page logic of full-screen video feeds, so Reels and Shorts stop
 *    half way between two videos. In the apps you pick, this service takes the keyboard touch
 *    stream (Android 14+ `onMotionEvent`) and plays a real touchscreen flick instead.
 * 2. **Speed Key** (the 3x3-dot key) + letter shortcuts, like BlackBerry's stock software.
 * 3. **Convenience key** press / hold / double-press actions. LineageOS sends it as ASSIST.
 */
class KeyboardFixerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var runner: ActionRunner
    private lateinit var router: KeyRouter
    private lateinit var injector: GestureInjector
    private var keyConfig = KeyConfig()
    private var reelsApps: Set<String> = emptySet()

    // Reels interception state
    private var intercepting = false
    private val geometry = TouchpadGeometry()
    private val classifier = SwipeClassifier()
    private var gestureStyle = ReelsStyle.SMART
    private var gestureHandled = false
    private var gestureCancelled = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reloadConfig()
        scheduleInterceptionUpdate()
    }
    private val updateInterception = Runnable { updateInterceptionNow() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        runner = ActionRunner(this, this).also { it.prepare() }
        injector = GestureInjector(this)
        router = KeyRouter(HandlerScheduler(handler), { runner.run(it) }) { letter ->
            Toast.makeText(this, getString(R.string.toast_unassigned_letter, letter), Toast.LENGTH_SHORT).show()
        }
        reloadConfig()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        instance = this
        updateInterceptionNow()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    private fun reloadConfig() {
        keyConfig = prefs.keyConfig()
        reelsApps = prefs.reelsApps
        val level = prefs.reelsSwipeLevel
        classifier.verticalThreshold = SwipeClassifier.thresholdForLevel(level, BASE_VERTICAL)
        classifier.horizontalThreshold = SwipeClassifier.thresholdForLevel(level, BASE_HORIZONTAL)
        if (::router.isInitialized) router.reset()
    }

    // ---------------------------------------------------------------- accessibility events

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> scheduleInterceptionUpdate()
        }
    }

    override fun onInterrupt() = Unit

    private fun scheduleInterceptionUpdate() {
        handler.removeCallbacks(updateInterception)
        handler.postDelayed(updateInterception, 120)
    }

    private fun updateInterceptionNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Diagnostics.reelsStatus = getString(R.string.status_reels_needs_14)
            return
        }
        val pkg = activeAppPackage()
        val want = when {
            !prefs.reelsEnabled -> false
            Diagnostics.keyTesterActive -> false
            pkg == null || pkg !in reelsApps -> false
            isTyping() -> false
            else -> true
        }
        Diagnostics.reelsStatus = if (want) getString(R.string.status_reels_active, pkg) else getString(R.string.status_reels_idle)
        if (want != intercepting) setIntercepting(want)
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun setIntercepting(on: Boolean) {
        val info = serviceInfo ?: return
        info.motionEventSources = if (on) InputDevice.SOURCE_TOUCHPAD else 0
        serviceInfo = info
        intercepting = on
        classifier.cancel()
        Log.d(TAG, "keyboard touch interception ${if (on) "on" else "off"}")
    }

    private fun activeAppPackage(): String? {
        val active = try {
            windows.firstOrNull { it.isActive }
        } catch (e: RuntimeException) {
            null
        }
        if (active != null && active.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
        return (active?.root ?: rootInActiveWindow)?.packageName?.toString()
    }

    /** True while a text field has focus or a keyboard window is on screen. */
    private fun isTyping(): Boolean {
        try {
            if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) return true
            return findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
        } catch (e: RuntimeException) {
            return false
        }
    }

    // ---------------------------------------------------------------- keyboard touch (Reels)

    override fun onMotionEvent(event: MotionEvent) {
        if (!intercepting || !TouchpadGeometry.isKeyboardTouch(event)) return
        geometry.update(event)
        val x = geometry.x(event.x)
        val y = geometry.y(event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                classifier.down(x, y, event.eventTime)
                gestureStyle = prefs.reelsStyle
                gestureHandled = false
                gestureCancelled = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                classifier.cancel()
                gestureCancelled = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dir = classifier.move(x, y, event.eventTime) ?: return
                if (gestureStyle != ReelsStyle.DRAG) onSwipe(dir)
            }
            MotionEvent.ACTION_UP -> {
                val dir = classifier.up(x, y, event.eventTime)
                if (gestureCancelled || gestureHandled) return
                if (gestureStyle == ReelsStyle.DRAG) {
                    replayDrag()
                    return
                }
                if (dir != null) onSwipe(dir)
                // Smart mode on a normal feed: the swipe is replayed as a finger drag instead.
                if (!gestureHandled && gestureStyle == ReelsStyle.SMART && classifier.hasFired) replayDrag()
            }
        }
    }

    /** A swipe crossed the threshold: flip a page, or (smart mode, not a pager) wait for UP. */
    private fun onSwipe(fingerDirection: SwipeDirection) {
        val dir = mapDirection(fingerDirection) ?: run {
            gestureHandled = true
            return
        }
        if (gestureStyle == ReelsStyle.SMART && !isPagerOnScreen()) return // replayed as a drag on UP
        gestureHandled = true
        if (!injector.isBusy()) injector.flick(dir)
    }

    private fun replayDrag() {
        gestureHandled = true
        var dx = classifier.totalDx
        var dy = classifier.totalDy
        if (abs(dx) > abs(dy) && !prefs.reelsHorizontal) return
        if (abs(dx) < MIN_DRAG && abs(dy) < MIN_DRAG) return
        if (prefs.reelsInvert) {
            dx = -dx
            dy = -dy
        }
        injector.drag(dx, dy, classifier.durationMillis)
    }

    /** Finger direction on the keyboard -> direction the content is pushed on screen. */
    private fun mapDirection(finger: SwipeDirection): SwipeDirection? {
        val horizontal = finger == SwipeDirection.LEFT || finger == SwipeDirection.RIGHT
        if (horizontal && !prefs.reelsHorizontal) return null
        if (!prefs.reelsInvert) return finger
        return when (finger) {
            SwipeDirection.UP -> SwipeDirection.DOWN
            SwipeDirection.DOWN -> SwipeDirection.UP
            SwipeDirection.LEFT -> SwipeDirection.RIGHT
            SwipeDirection.RIGHT -> SwipeDirection.LEFT
        }
    }

    private fun isPagerOnScreen(): Boolean {
        val wm = getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        return try {
            PagerDetector.isFullScreenPager(rootInActiveWindow, w, h)
        } catch (e: RuntimeException) {
            true // when in doubt inside a Reels app, flip a page
        }
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Diagnostics.keyTesterActive) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (event.action == KeyEvent.ACTION_DOWN && classifier.isActive) {
            // A key press means the finger on the keyboard is typing, not swiping.
            classifier.cancel()
            gestureCancelled = true
        }
        val cfg = keyConfig
        val isSpeedPress = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode in cfg.speedKeyCodes
        val typing = isSpeedPress && cfg.speedPassThroughWhileTyping && isTyping()
        val input = KeyInput(
            isDown = event.action == KeyEvent.ACTION_DOWN,
            keyCode = event.keyCode,
            repeatCount = event.repeatCount,
            isExternal = event.device?.isExternal == true,
        )
        val consumed = router.onKey(input, cfg, typing)
        if (consumed && event.keyCode == cfg.convenienceKeyCode) {
            Diagnostics.lastConvenienceHandled = SystemClock.uptimeMillis()
        }
        return consumed
    }

    fun runAction(action: KeyAction) = runner.run(action)

    companion object {
        private const val TAG = "Key2Fixer"
        private const val BASE_VERTICAL = 0.09f
        private const val BASE_HORIZONTAL = 0.14f
        private const val MIN_DRAG = 0.02f

        @Volatile
        var instance: KeyboardFixerService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}
