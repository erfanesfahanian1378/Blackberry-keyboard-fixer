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
import io.github.erfanesfahanian.key2fixer.gesture.DoubleTapDetector
import io.github.erfanesfahanian.key2fixer.gesture.SwipeClassifier
import io.github.erfanesfahanian.key2fixer.gesture.SwipeDirection
import io.github.erfanesfahanian.key2fixer.gesture.TouchpadGeometry
import io.github.erfanesfahanian.key2fixer.keys.ActionRunner
import io.github.erfanesfahanian.key2fixer.keys.KeyAction
import io.github.erfanesfahanian.key2fixer.keys.KeyConfig
import io.github.erfanesfahanian.key2fixer.keys.KeyInput
import io.github.erfanesfahanian.key2fixer.keys.KeyRouter
import io.github.erfanesfahanian.key2fixer.keys.VideoAction
import io.github.erfanesfahanian.key2fixer.keys.VideoKeys
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
 * 4. **Video shortcuts**: in those apps single keys pause, like, open comments, repost... and
 *    holding P plays at 2x; a double tap on the keyboard surface likes the video.
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

    // Video shortcuts state
    /** A chosen video app is in front and nothing is being typed. */
    private var videoAppActive = false
    private val doubleTap = DoubleTapDetector()
    private var lastKeyTime = 0L
    /** Key whose press started a video action; its repeats and release are swallowed too. */
    private var videoKeyDown = -1
    private var pagerCheckedAt = 0L
    private var pagerCached = false

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
        val pkg = activeAppPackage()
        val want = when {
            !prefs.reelsEnabled -> false
            Diagnostics.keyTesterActive -> false
            pkg == null || pkg !in reelsApps -> false
            isTyping() -> false
            else -> true
        }
        videoAppActive = want
        if (!want) releaseVideoKey()
        // Video key shortcuts work on any version; keyboard-touch interception needs Android 14.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Diagnostics.reelsStatus = getString(R.string.status_reels_needs_14)
            return
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
        doubleTap.reset()
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
                doubleTap.reset()
            }
            MotionEvent.ACTION_MOVE -> {
                val dir = classifier.move(x, y, event.eventTime) ?: return
                if (gestureStyle != ReelsStyle.DRAG) onSwipe(dir)
            }
            MotionEvent.ACTION_UP -> {
                val dir = classifier.up(x, y, event.eventTime)
                if (gestureCancelled) {
                    doubleTap.reset() // a key was pressed: typing, not tapping
                    return
                }
                if (!gestureHandled && !classifier.hasFired && onKeyboardTap(event.eventTime)) return
                if (gestureHandled) return
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

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
    }

    private fun isPagerOnScreen(): Boolean {
        val (w, h) = screenSize()
        return try {
            PagerDetector.isFullScreenPager(rootInActiveWindow, w, h)
        } catch (e: RuntimeException) {
            true // when in doubt inside a Reels app, flip a page
        }
    }

    // ---------------------------------------------------------------- video shortcuts

    /** A light touch on the keyboard ended; two in a row run the double-tap action. */
    private fun onKeyboardTap(upTime: Long): Boolean {
        val travel = kotlin.math.hypot(classifier.totalDx, classifier.totalDy)
        if (!doubleTap.onTouch(classifier.startTime, upTime, travel, lastKeyTime)) return false
        if (!prefs.videoShortcutsEnabled) return false
        val action = prefs.videoDoubleTap
        if (action == VideoAction.NONE || action == VideoAction.SPEED_2X) return false
        gestureHandled = true
        runVideoAction(action, holdWhileKeyDown = false)
        return true
    }

    /** Single keys in a video app. Returns true if the key was used (and must not reach the app). */
    private fun handleVideoKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (code != videoKeyDown) return false
            videoKeyDown = -1
            injector.endHold() // lifts the 2x finger; no-op for other actions
            return true
        }
        if (code == videoKeyDown) return true // auto-repeat while held
        if (event.repeatCount > 0 || !videoAppActive || !prefs.videoShortcutsEnabled) return false
        if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isSymPressed) return false
        val slot = VideoKeys.slotFor(code) ?: return false
        val action = prefs.videoAction(slot)
        if (action == VideoAction.NONE) return false
        if (isTyping()) return false // a text field may have just got focus
        if (!runVideoAction(action, holdWhileKeyDown = true)) return false
        videoKeyDown = code
        return true
    }

    private fun releaseVideoKey() {
        if (videoKeyDown == -1) return
        videoKeyDown = -1
        injector.endHold()
    }

    private fun runVideoAction(action: VideoAction, holdWhileKeyDown: Boolean): Boolean {
        if (action.pressesButton) {
            val (w, h) = screenSize()
            return try {
                ButtonFinder.findAndClick(rootInActiveWindow, buttonKeywords(action), w, h)
            } catch (e: RuntimeException) {
                false
            }
        }
        if (!isVideoOnScreen()) return false
        return when (action) {
            VideoAction.PLAY_PAUSE -> injector.tap(VIDEO_X, VIDEO_Y)
            VideoAction.LIKE -> injector.doubleTap(VIDEO_X, VIDEO_Y)
            // Reels, Shorts and TikTok play at 2x while a finger rests on the right side.
            VideoAction.SPEED_2X -> holdWhileKeyDown && injector.beginHold(SPEED_X, SPEED_Y)
            VideoAction.NEXT_VIDEO -> injector.flick(if (prefs.reelsInvert) SwipeDirection.DOWN else SwipeDirection.UP)
            VideoAction.PREVIOUS_VIDEO -> injector.flick(if (prefs.reelsInvert) SwipeDirection.UP else SwipeDirection.DOWN)
            else -> false
        }
    }

    /** A full-screen video is showing (checked at most once a second; the tree walk costs IPC). */
    private fun isVideoOnScreen(): Boolean {
        if (prefs.reelsStyle == ReelsStyle.PAGE) return true
        val now = SystemClock.uptimeMillis()
        if (now - pagerCheckedAt > PAGER_CACHE_MILLIS) {
            pagerCached = isPagerOnScreen()
            pagerCheckedAt = now
        }
        return pagerCached
    }

    private fun buttonKeywords(action: VideoAction): List<String> = when (action) {
        VideoAction.COMMENTS -> listOf("comment")
        VideoAction.REPOST -> listOf("repost")
        VideoAction.SHARE -> listOf("share", "send")
        VideoAction.SAVE -> listOf("save", "bookmark")
        else -> emptyList()
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Diagnostics.keyTesterActive) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (event.action == KeyEvent.ACTION_DOWN) lastKeyTime = event.eventTime
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
            if (input.isDown && input.repeatCount == 0) Diagnostics.recordConvenience(Diagnostics.ConvenienceRoute.KEY_EVENT)
        }
        if (consumed) return true
        return handleVideoKey(event)
    }

    fun runAction(action: KeyAction) = runner.run(action)

    companion object {
        private const val TAG = "Key2Fixer"
        private const val BASE_VERTICAL = 0.09f
        private const val BASE_HORIZONTAL = 0.14f
        private const val MIN_DRAG = 0.02f
        private const val PAGER_CACHE_MILLIS = 1000L
        /** Where taps land: the middle of the video, clear of captions and the button column. */
        private const val VIDEO_X = 0.5f
        private const val VIDEO_Y = 0.42f
        /** Where the 2x finger rests: right side, above the like/comment buttons. */
        private const val SPEED_X = 0.85f
        private const val SPEED_Y = 0.35f

        @Volatile
        var instance: KeyboardFixerService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}
