package io.github.erfanesfahanian.key2fixer.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Tiny view builders so the settings screens stay plain framework code (no AndroidX). */
class Ui(private val activity: Activity) {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(24))
    }
    private val refreshers = mutableListOf<() -> Unit>()

    fun install(): ScrollView {
        val scroll = ScrollView(activity).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Keep content clear of the status and navigation bars (edge-to-edge on Android 15+).
            fitsSystemWindows = true
        }
        activity.setContentView(scroll)
        return scroll
    }

    /** Re-reads every dynamic summary (call from onResume). */
    fun refresh() = refreshers.forEach { it() }

    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        activity.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) activity.getColor(tv.resourceId) else tv.data
    }

    fun section(title: String) {
        root.addView(TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(attrColor(android.R.attr.colorAccent))
            setPadding(dp(20), dp(24), dp(20), dp(6))
        })
    }

    fun note(text: () -> String): TextView {
        val tv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val update = { tv.text = text() }
        update()
        refreshers += update
        root.addView(tv)
        return tv
    }

    private fun twoLine(title: String): Triple<LinearLayout, TextView, TextView> {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            val tv = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        }
        val s = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(attrColor(android.R.attr.textColorSecondary))
        }
        texts.addView(t)
        texts.addView(s)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return Triple(row, t, s)
    }

    fun clickRow(title: String, summary: () -> String, onClick: () -> Unit): View {
        val (row, _, s) = twoLine(title)
        val update = {
            val text = summary()
            s.text = text
            s.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        update()
        refreshers += update
        row.setOnClickListener {
            onClick()
            update()
        }
        root.addView(row)
        return row
    }

    fun switchRow(title: String, summary: String, get: () -> Boolean, set: (Boolean) -> Unit): Switch {
        val (row, _, s) = twoLine(title)
        s.text = summary
        s.visibility = if (summary.isEmpty()) View.GONE else View.VISIBLE
        val sw = Switch(activity).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, checked -> set(checked) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        refreshers += { if (sw.isChecked != get()) sw.isChecked = get() }
        root.addView(row)
        return sw
    }

    fun seekRow(title: String, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit, label: (Int) -> String) {
        val (row, _, s) = twoLine(title)
        row.isClickable = false
        row.background = null
        val texts = row.getChildAt(0) as LinearLayout
        val seek = SeekBar(activity).apply {
            this.max = max - min
            progress = get() - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    s.text = label(progress + min)
                    if (fromUser) set(progress + min)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        s.text = label(get())
        texts.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row)
    }

    fun divider() {
        root.addView(View(activity).apply {
            setBackgroundColor(attrColor(android.R.attr.textColorSecondary))
            alpha = 0.2f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            setMargins(dp(20), dp(8), dp(20), dp(8))
        })
    }

    companion object {
        fun dp(context: Context, v: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
    }
}
