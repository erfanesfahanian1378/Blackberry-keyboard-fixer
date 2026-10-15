package io.github.erfanesfahanian.key2fixer.ime

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.erfanesfahanian.key2fixer.gesture.StepAccumulator
import io.github.erfanesfahanian.key2fixer.gesture.SwipeClassifier
import io.github.erfanesfahanian.key2fixer.gesture.SwipeDirection
import io.github.erfanesfahanian.key2fixer.gesture.TouchpadGeometry
import io.github.erfanesfahanian.key2fixer.util.Diagnostics
import io.github.erfanesfahanian.key2fixer.util.HorizontalSwipe
import io.github.erfanesfahanian.key2fixer.util.Prefs
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * A keyboard for the KEY2's physical keys that brings back BlackBerry's "swipe up to pick a
 * prediction".
 *
 * Typing itself is left completely to Android: every key press passes straight through to the
 * app, so Alt/Shift/Sym behave exactly as before. The keyboard only adds the three-word bar and
 * reads swipes on the keyboard surface (the "touch_keypad" device, SOURCE_TOUCHPAD), which reach
 * the active input method before the app turns them into scrolling.
 */
class PredictiveKeyboardService : InputMethodService() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var userDict: UserDictionary

    @Volatile
    private var predictor: WordPredictor? = null
    private var strip: SuggestionStripView? = null
    private var suggestions: List<String> = emptyList()

    /** The focused editor is a normal text field that may show predictions. */
    private var active = false
    private var learnAllowed = false
    private var showRequested = false
    private var shiftHeld = false
    private var lastSpaceTime = 0L

    // Keyboard-surface gesture state
    private val geometry = TouchpadGeometry()
    private val classifier = SwipeClassifier()
    private val cursorSteps = StepAccumulator(CURSOR_STEP)
    private var gesture = Gesture.NONE
    private var gestureSlot = 1
    private var lastPadX = 0f

    private enum class Gesture { NONE, PENDING, CURSOR, DONE }

    private val refresh = Runnable { refreshSuggestions() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_FORGET) io.execute { userDict.clear(); runCatching { userDict.save() } }
    }
    private val save = Runnable { io.execute { runCatching { userDict.save() } } }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        userDict = UserDictionary(File(filesDir, Prefs.USER_WORDS_FILE))
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        io.execute {
            try {
                userDict.load()
                val words = assets.open(WORD_LIST).bufferedReader().use { WordPredictor.parseWordList(it) }
                predictor = WordPredictor(words, userDict)
                handler.post { scheduleRefresh() }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the word list", e)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        io.execute { runCatching { userDict.save() } }
        io.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View =
        SuggestionStripView(this).also { view ->
            view.onPick = { slot -> pickSlot(slot) }
            view.setSuggestions(suggestions)
            strip = view
        }

    // The bar is the whole "keyboard", so show it even though a hardware keyboard is present.
    override fun onEvaluateInputViewShown(): Boolean = active || super.onEvaluateInputViewShown()

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean =
        active || super.onShowInputRequested(flags, configChange)

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        active = isPredictable(attribute)
        learnAllowed = active && (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        if (!restarting) showRequested = false
        resetGesture()
        scheduleRefresh()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        scheduleRefresh()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        active = false
        suggestions = emptyList()
        strip?.setSuggestions(suggestions)
        resetGesture()
        handler.removeCallbacks(save)
        handler.post(save)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        scheduleRefresh()
    }

    private fun isPredictable(info: EditorInfo): Boolean {
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> false
            else -> true
        }
    }

    // ------------------------------------------------------------------ hardware keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) shiftHeld = true
        // Pressing a key means the finger on the keyboard surface is typing, not swiping.
        if (classifier.isActive) {
            classifier.cancel()
            gesture = Gesture.DONE
        }
        if (active && !Diagnostics.keyTesterActive) {
            if (!isInputViewShown && !showRequested && event.isPrintingKey && event.repeatCount == 0) {
                // The field got focus without asking for a keyboard; bring the bar up now.
                showRequested = true
                requestShowSelf(0)
            }
            if (event.repeatCount == 0) {
                when {
                    keyCode == KeyEvent.KEYCODE_SPACE && !event.isAltPressed -> {
                        if (handleDoubleSpace()) return true
                        learnWordBeforeCursor()
                    }
                    keyCode == KeyEvent.KEYCODE_ENTER || isPunctuation(event) -> learnWordBeforeCursor()
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) shiftHeld = false
        return super.onKeyUp(keyCode, event)
    }

    private fun isPunctuation(event: KeyEvent): Boolean {
        val c = event.getUnicodeChar(event.metaState)
        return c > 0 && c.toChar() in ".,!?;:)"
    }

    /** Two quick spaces after a word become ". " (BlackBerry behaviour). */
    private fun handleDoubleSpace(): Boolean {
        val now = SystemClock.uptimeMillis()
        val quick = now - lastSpaceTime < DOUBLE_SPACE_MILLIS
        lastSpaceTime = now
        if (!quick || !prefs.imeDoubleSpacePeriod) return false
        val ic = currentInputConnection ?: return false
        if (!TextContext.endsWithWordAndSpace(ic.getTextBeforeCursor(3, 0))) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(". ", 1)
        ic.endBatchEdit()
        lastSpaceTime = 0
        return true
    }

    private fun learnWordBeforeCursor() {
        if (!learnAllowed || !prefs.imeLearn) return
        val before = currentInputConnection?.getTextBeforeCursor(64, 0) ?: return
        val word = TextContext.currentWordPrefix(before)
        if (word.length < 2) return
        userDict.learn(word, TextContext.previousWord(before))
        scheduleSave()
    }

    // ------------------------------------------------------------------ suggestions

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 25)
    }

    private fun scheduleSave() {
        handler.removeCallbacks(save)
        handler.postDelayed(save, 8000)
    }

    private fun refreshSuggestions() {
        val p = predictor
        val ic = currentInputConnection
        suggestions = if (!active || p == null || ic == null) {
            emptyList()
        } else {
            val before = ic.getTextBeforeCursor(64, 0)
            val word = TextContext.currentWordPrefix(before)
            val previous = TextContext.previousWord(before)
            val ranked = when {
                word.isNotEmpty() -> p.suggest(word, previous)
                prefs.imeNextWord -> p.suggest("", previous)
                else -> emptyList()
            }
            val sentenceStart = word.isEmpty() &&
                ic.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) != 0
            ranked.map { TextContext.applyCase(it, word, sentenceStart) }
        }
        strip?.setSuggestions(suggestions)
    }

    /** Inserts the word shown in [slot] (0 = left, 1 = middle, 2 = right). */
    private fun pickSlot(slot: Int) {
        val word = strip?.wordAt(slot)?.takeIf { it.isNotEmpty() } ?: return
        commitSuggestion(word)
        strip?.flash(slot)
    }

    private fun commitSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)
        val after = ic.getTextAfterCursor(64, 0)
        val prefix = TextContext.currentWordPrefix(before)
        val suffix = TextContext.currentWordSuffix(after)
        val spaceFollows = after?.getOrNull(suffix.length) == ' '
        ic.beginBatchEdit()
        ic.finishComposingText()
        val deleteAfter = suffix.length + if (spaceFollows) 1 else 0
        if (prefix.isNotEmpty() || deleteAfter > 0) ic.deleteSurroundingText(prefix.length, deleteAfter)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (learnAllowed && prefs.imeLearn) {
            userDict.learn(word, TextContext.previousWord(before))
            scheduleSave()
        }
        lastSpaceTime = 0
        scheduleRefresh()
    }

    // ------------------------------------------------------------------ keyboard surface swipes

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!active || Diagnostics.keyTesterActive || !TouchpadGeometry.isKeyboardTouch(event)) {
            return super.onGenericMotionEvent(event)
        }
        val selectOn = prefs.imeSwipeSelect && isInputViewShown
        val horizontal = prefs.imeHorizontal
        if (!selectOn && horizontal == HorizontalSwipe.OFF) return super.onGenericMotionEvent(event)

        geometry.update(event)
        val x = geometry.x(event.x)
        val y = geometry.y(event.y).let { if (prefs.imeInvert) -it else it }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                configureThresholds(horizontal)
                classifier.down(x, y, event.eventTime)
                gesture = Gesture.PENDING
                gestureSlot = (geometry.xFraction(event.x) * 3).toInt().coerceIn(0, 2)
                cursorSteps.reset()
                lastPadX = x
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                classifier.cancel()
                gesture = Gesture.DONE
            }
            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    Gesture.PENDING -> classifier.move(x, y, event.eventTime)?.let { onSwipe(it, selectOn, horizontal) }
                    Gesture.CURSOR -> moveCursor(cursorSteps.add(x - lastPadX))
                    else -> Unit
                }
                lastPadX = x
            }
            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.PENDING) classifier.up(x, y, event.eventTime)?.let { onSwipe(it, selectOn, horizontal) }
                if (gesture == Gesture.CURSOR) moveCursor(cursorSteps.add(x - lastPadX))
                gesture = Gesture.NONE
            }
        }
        return true
    }

    private fun configureThresholds(horizontal: HorizontalSwipe) {
        val level = prefs.imeSwipeLevel
        classifier.verticalThreshold = SwipeClassifier.thresholdForLevel(level, BASE_VERTICAL)
        classifier.horizontalThreshold = SwipeClassifier.thresholdForLevel(
            level, if (horizontal == HorizontalSwipe.DELETE_WORD) BASE_DELETE_WORD else BASE_CURSOR,
        )
    }

    private fun onSwipe(direction: SwipeDirection, selectOn: Boolean, horizontal: HorizontalSwipe) {
        gesture = Gesture.DONE
        when (direction) {
            SwipeDirection.UP -> if (selectOn) pickSlot(gestureSlot)
            SwipeDirection.DOWN -> Unit
            SwipeDirection.LEFT, SwipeDirection.RIGHT -> when (horizontal) {
                HorizontalSwipe.CURSOR -> {
                    gesture = Gesture.CURSOR
                    cursorSteps.reset()
                    // Count the travel so far, minus the dead zone, so the cursor starts gently.
                    val sign = if (direction == SwipeDirection.LEFT) -1f else 1f
                    moveCursor(cursorSteps.add(classifier.totalDx - sign * classifier.horizontalThreshold * 0.5f))
                }
                HorizontalSwipe.DELETE_WORD -> if (direction == SwipeDirection.LEFT) deleteWordBeforeCursor()
                HorizontalSwipe.OFF -> Unit
            }
        }
    }

    private fun moveCursor(steps: Int) {
        if (steps == 0) return
        val ic = currentInputConnection ?: return
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val meta = if (shiftHeld) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        repeat(abs(steps)) { sendKey(ic, code, meta) }
    }

    private fun sendKey(ic: InputConnection, code: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
    }

    /** Deletes the word before the cursor and the spaces after it, like BlackBerry's swipe-left. */
    private fun deleteWordBeforeCursor() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0) ?: return
        var n = 0
        while (n < before.length && before[before.length - 1 - n] == ' ') n++
        while (n < before.length && TextContext.isWordChar(before[before.length - 1 - n])) n++
        if (n == 0 && before.isNotEmpty()) n = 1
        if (n > 0) ic.deleteSurroundingText(n, 0)
    }

    private fun resetGesture() {
        classifier.cancel()
        gesture = Gesture.NONE
    }

    companion object {
        private const val TAG = "Key2FixerIME"
        private const val WORD_LIST = "dict/en_US.txt"
        private const val DOUBLE_SPACE_MILLIS = 700L
        private const val BASE_VERTICAL = 0.08f
        private const val BASE_CURSOR = 0.05f
        private const val BASE_DELETE_WORD = 0.20f
        private const val CURSOR_STEP = 0.035f
    }
}
