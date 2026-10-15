package io.github.erfanesfahanian.key2fixer.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.erfanesfahanian.key2fixer.R
import io.github.erfanesfahanian.key2fixer.gesture.SwipeClassifier
import io.github.erfanesfahanian.key2fixer.gesture.TouchpadGeometry
import io.github.erfanesfahanian.key2fixer.util.Diagnostics
import io.github.erfanesfahanian.key2fixer.util.Prefs

/**
 * Shows the raw key codes and keyboard-touch events the phone produces, and can "learn" which
 * code the Speed Key or Convenience key sends on your ROM. While this screen is open the
 * services stand aside so every event arrives here unchanged.
 */
class KeyTesterActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var header: TextView
    private lateinit var touchInfo: TextView
    private lateinit var log: TextView
    private val lines = ArrayDeque<String>()
    private var learn: String? = null
    private val geometry = TouchpadGeometry()
    private val classifier = SwipeClassifier()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.tester_title)
        prefs = Prefs(this)
        learn = intent.getStringExtra(EXTRA_LEARN)
        val pad = Ui.dp(this, 20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        header = TextView(this).apply { textSize = 16f }
        touchInfo = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        column.addView(header)
        column.addView(touchInfo)
        column.addView(log)
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
        updateHeader()
        touchInfo.text = getString(R.string.tester_touch_idle)
    }

    override fun onResume() {
        super.onResume()
        Diagnostics.keyTesterActive = true
    }

    override fun onPause() {
        Diagnostics.keyTesterActive = false
        super.onPause()
    }

    private fun updateHeader() {
        header.text = when (learn) {
            LEARN_SPEED -> getString(R.string.tester_learn_speed)
            LEARN_CONVENIENCE -> getString(R.string.tester_learn_convenience)
            else -> getString(R.string.tester_explain)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device
        add(
            "%s %s scan=%d repeat=%d dev=%s%s".format(
                if (event.action == KeyEvent.ACTION_DOWN) "↓" else "↑",
                KeyEvent.keyCodeToString(event.keyCode),
                event.scanCode,
                event.repeatCount,
                dev?.name ?: "virtual",
                if (dev?.isExternal == true) " (external)" else "",
            ),
        )
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val target = learn
        if (target != null && event.action == KeyEvent.ACTION_UP) {
            val name = KeyEvent.keyCodeToString(event.keyCode)
            when (target) {
                LEARN_SPEED -> prefs.speedKeyCodes = setOf(event.keyCode)
                LEARN_CONVENIENCE -> prefs.convenienceKeyCode = event.keyCode
            }
            Toast.makeText(this, getString(R.string.tester_learned, name), Toast.LENGTH_LONG).show()
            learn = null
            updateHeader()
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return super.dispatchGenericMotionEvent(event)
        geometry.update(event)
        val x = geometry.x(event.x)
        val y = geometry.y(event.y)
        val swipe = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                classifier.down(x, y, event.eventTime)
                null
            }
            MotionEvent.ACTION_MOVE -> classifier.move(x, y, event.eventTime)
            MotionEvent.ACTION_UP -> classifier.up(x, y, event.eventTime)
            else -> null
        }
        touchInfo.text = getString(
            R.string.tester_touch_info,
            event.device?.name ?: "?",
            geometry.describe(),
            event.x, event.y,
            MotionEvent.actionToString(event.actionMasked),
            event.pointerCount,
        )
        if (swipe != null) add("swipe $swipe  (dx=%.2f dy=%.2f pad widths)".format(classifier.totalDx, classifier.totalDy))
        return true
    }

    private fun add(line: String) {
        lines.addFirst(line)
        while (lines.size > 40) lines.removeLast()
        log.text = lines.joinToString("\n")
    }

    companion object {
        const val EXTRA_LEARN = "learn"
        const val LEARN_SPEED = "speed"
        const val LEARN_CONVENIENCE = "convenience"
    }
}
