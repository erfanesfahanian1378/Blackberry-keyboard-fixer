package io.github.erfanesfahanian.key2fixer.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.github.erfanesfahanian.key2fixer.R

/**
 * BlackBerry-style prediction bar: three slots lined up with the left, middle and right thirds
 * of the keyboard. Swipe up on that part of the keyboard (or tap the slot) to insert the word.
 * The best suggestion sits in the middle.
 */
class SuggestionStripView(context: Context) : View(context) {
    var onPick: ((slot: Int) -> Unit)? = null

    private val slots = arrayOf("", "", "")
    private var flashSlot = -1
    private var flashUntil = 0L

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val background = if (dark) Color.rgb(0x20, 0x21, 0x24) else Color.rgb(0xF1, 0xF3, 0xF4)
    private val flashPaint = Paint().apply {
        color = if (dark) Color.rgb(0x3C, 0x40, 0x43) else Color.rgb(0xD2, 0xE3, 0xFC)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.rgb(0xE8, 0xEA, 0xED) else Color.rgb(0x20, 0x21, 0x24)
        textSize = sp(17f)
        textAlign = Paint.Align.CENTER
    }
    private val boldPaint = TextPaint(textPaint).apply { typeface = Typeface.DEFAULT_BOLD }
    private val dividerPaint = Paint().apply {
        color = if (dark) Color.rgb(0x5F, 0x63, 0x68) else Color.rgb(0xBD, 0xC1, 0xC6)
        strokeWidth = dp(1f)
    }
    private val hintPaint = TextPaint(textPaint).apply {
        textSize = sp(12f)
        color = dividerPaint.color
    }

    /** [ranked] is best-first; it is laid out as [2nd, 1st, 3rd]. */
    fun setSuggestions(ranked: List<String>) {
        slots[1] = ranked.getOrNull(0).orEmpty()
        slots[0] = ranked.getOrNull(1).orEmpty()
        slots[2] = ranked.getOrNull(2).orEmpty()
        invalidate()
    }

    /** Word shown in [slot] (0 = left, 1 = middle, 2 = right), or "" if empty. */
    fun wordAt(slot: Int): String = slots.getOrNull(slot).orEmpty()

    fun flash(slot: Int) {
        flashSlot = slot
        flashUntil = SystemClock.uptimeMillis() + FLASH_MILLIS
        invalidate()
        postInvalidateDelayed(FLASH_MILLIS + 16)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(42f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        val cell = width / 3f
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        val flashing = SystemClock.uptimeMillis() < flashUntil
        for (i in 0..2) {
            val left = i * cell
            if (flashing && i == flashSlot) canvas.drawRect(left, 0f, left + cell, height.toFloat(), flashPaint)
            val paint = if (i == 1) boldPaint else textPaint
            val text = TextUtils.ellipsize(slots[i], paint, cell - dp(12f), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, left + cell / 2f, baseline, paint)
        }
        val inset = height * 0.25f
        canvas.drawLine(cell, inset, cell, height - inset, dividerPaint)
        canvas.drawLine(cell * 2, inset, cell * 2, height - inset, dividerPaint)
        if (slots.all { it.isEmpty() }) {
            val hint = context.getString(R.string.strip_hint)
            canvas.drawText(hint, width / 2f, height / 2f - (hintPaint.descent() + hintPaint.ascent()) / 2f, hintPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val slot = (event.x / (width / 3f)).toInt().coerceIn(0, 2)
            if (slots[slot].isNotEmpty()) onPick?.invoke(slot)
        }
        return true
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private const val FLASH_MILLIS = 180L
    }
}
