package io.github.erfanesfahanian.key2fixer.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.erfanesfahanian.key2fixer.R

/** Colours shared by the keyboard's bar, button and clipboard panel. */
class ImeColors(context: Context) {
    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val background = if (dark) Color.rgb(0x20, 0x21, 0x24) else Color.rgb(0xF1, 0xF3, 0xF4)
    val card = if (dark) Color.rgb(0x30, 0x31, 0x34) else Color.WHITE
    val text = if (dark) Color.rgb(0xE8, 0xEA, 0xED) else Color.rgb(0x20, 0x21, 0x24)
    val secondary = if (dark) Color.rgb(0x9A, 0xA0, 0xA6) else Color.rgb(0x5F, 0x63, 0x68)
}

/**
 * The clipboard history panel shown above the word bar: tap a clip to paste it, 📌 to pin it
 * (pinned clips are kept), ✕ to delete it.
 */
class ClipboardPanelView(context: Context, private val listener: Listener) : LinearLayout(context) {
    interface Listener {
        fun onPasteClip(text: String)
        fun onTogglePin(text: String)
        fun onDeleteClip(text: String)
        fun onClearClips()
        fun onCloseClipboard()
    }

    private val colors = ImeColors(context)
    private val list = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.background)
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(4), dp(2))
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.clipboard_title)
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button(context.getString(R.string.clipboard_clear)) { listener.onClearClips() })
        header.addView(button("✕") { listener.onCloseClipboard() })
        addView(header)
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))
    }

    /** Shows [clips]; [enabled] false shows a hint that history is switched off. */
    fun show(clips: List<ClipboardHistory.Clip>, enabled: Boolean) {
        list.removeAllViews()
        if (clips.isEmpty()) {
            list.addView(TextView(context).apply {
                text = context.getString(if (enabled) R.string.clipboard_empty else R.string.clipboard_disabled)
                setTextColor(colors.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }
        for (clip in clips) list.addView(row(clip))
    }

    private fun row(clip: ClipboardHistory.Clip): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.card)
        }
        row.addView(TextView(context).apply {
            text = clip.text
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(16), dp(10), dp(8), dp(10))
            isClickable = true
            setOnClickListener { listener.onPasteClip(clip.text) }
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(if (clip.pinned) "📌" else "📍", alpha = if (clip.pinned) 1f else 0.45f) {
            listener.onTogglePin(clip.text)
        })
        row.addView(button("✕") { listener.onDeleteClip(clip.text) })
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(3))
            addView(row)
        }
    }

    private fun button(label: String, alpha: Float = 1f, onClick: () -> Unit) = TextView(context).apply {
        text = label
        this.alpha = alpha
        setTextColor(colors.secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(40)
        setPadding(dp(8), 0, dp(8), 0)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
