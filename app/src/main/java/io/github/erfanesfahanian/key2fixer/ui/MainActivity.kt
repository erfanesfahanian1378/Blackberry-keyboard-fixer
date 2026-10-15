package io.github.erfanesfahanian.key2fixer.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import io.github.erfanesfahanian.key2fixer.BuildConfig
import io.github.erfanesfahanian.key2fixer.R
import io.github.erfanesfahanian.key2fixer.a11y.KeyboardFixerService
import io.github.erfanesfahanian.key2fixer.ime.PredictiveKeyboardService
import io.github.erfanesfahanian.key2fixer.keys.KeyAction
import io.github.erfanesfahanian.key2fixer.util.Diagnostics
import io.github.erfanesfahanian.key2fixer.util.HorizontalSwipe
import io.github.erfanesfahanian.key2fixer.util.Prefs
import io.github.erfanesfahanian.key2fixer.util.ReelsStyle

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        build()
        ui.install()
    }

    override fun onResume() {
        super.onResume()
        ui.refresh()
    }

    private fun check(ok: Boolean) = if (ok) "✅ " else "⚠️ "

    private fun build() = with(ui) {
        // ------------------------------------------------------------- setup
        section(getString(R.string.section_setup))
        clickRow(getString(R.string.setup_accessibility), {
            check(KeyboardFixerService.isRunning) + getString(
                if (KeyboardFixerService.isRunning) R.string.setup_accessibility_on else R.string.setup_accessibility_off,
            )
        }) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        clickRow(getString(R.string.setup_restricted), { getString(R.string.setup_restricted_summary) }) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        clickRow(getString(R.string.setup_keyboard), { keyboardStatus() }) { onKeyboardRowClicked() }
        clickRow(getString(R.string.setup_assistant), { getString(R.string.setup_assistant_summary) }) {
            open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        note {
            val sdk = Build.VERSION.SDK_INT
            getString(R.string.setup_android_version, Build.VERSION.RELEASE, sdk) +
                if (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) "\n" + getString(R.string.status_reels_needs_14) else ""
        }

        // ------------------------------------------------------------- reels
        section(getString(R.string.section_reels))
        note { getString(R.string.reels_explain) }
        switchRow(getString(R.string.reels_enable), getString(R.string.reels_enable_summary), { prefs.reelsEnabled }) {
            prefs.reelsEnabled = it
        }
        clickRow(getString(R.string.reels_apps), { getString(R.string.reels_apps_summary, prefs.reelsApps.size) }) {
            startActivity(Intent(this@MainActivity, ReelsAppsActivity::class.java))
        }
        clickRow(getString(R.string.reels_style), { styleLabel(prefs.reelsStyle) }) { pickReelsStyle() }
        seekRow(getString(R.string.swipe_distance), 1, 10, { prefs.reelsSwipeLevel }, { prefs.reelsSwipeLevel = it }) {
            getString(R.string.swipe_distance_value, it)
        }
        switchRow(getString(R.string.reels_horizontal), getString(R.string.reels_horizontal_summary), { prefs.reelsHorizontal }) {
            prefs.reelsHorizontal = it
        }
        switchRow(getString(R.string.invert_direction), getString(R.string.invert_direction_summary), { prefs.reelsInvert }) {
            prefs.reelsInvert = it
        }
        note { Diagnostics.reelsStatus.ifEmpty { getString(R.string.status_reels_idle) } }

        // ------------------------------------------------------------- speed key
        section(getString(R.string.section_speed))
        note { getString(R.string.speed_explain) }
        switchRow(getString(R.string.speed_enable), "", { prefs.speedEnabled }) { prefs.speedEnabled = it }
        clickRow(getString(R.string.speed_shortcuts), {
            val n = ('A'..'Z').count { prefs.letterAction(it, false) != KeyAction.None || prefs.letterAction(it, true) != KeyAction.None }
            getString(R.string.speed_shortcuts_summary, n)
        }) { startActivity(Intent(this@MainActivity, ShortcutsActivity::class.java)) }
        clickRow(getString(R.string.speed_key_code), {
            prefs.speedKeyCodes.joinToString { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
        }) { learn(KeyTesterActivity.LEARN_SPEED) }
        clickRow(getString(R.string.speed_tap_alone), { actionLabel(this@MainActivity, prefs.speedTapAction) }) {
            ActionPicker.show(this@MainActivity, getString(R.string.speed_tap_alone)) { prefs.speedTapAction = it; ui.refresh() }
        }
        switchRow(getString(R.string.speed_passthrough), getString(R.string.speed_passthrough_summary), { prefs.speedPassThroughWhileTyping }) {
            prefs.speedPassThroughWhileTyping = it
        }

        // ------------------------------------------------------------- convenience key
        section(getString(R.string.section_convenience))
        note { getString(R.string.convenience_explain) }
        switchRow(getString(R.string.convenience_enable), "", { prefs.convenienceEnabled }) { prefs.convenienceEnabled = it }
        clickRow(getString(R.string.convenience_press), { actionLabel(this@MainActivity, prefs.convenienceTap) }) {
            ActionPicker.show(this@MainActivity, getString(R.string.convenience_press)) { prefs.convenienceTap = it; ui.refresh() }
        }
        clickRow(getString(R.string.convenience_hold), { actionLabel(this@MainActivity, prefs.convenienceHold) }) {
            ActionPicker.show(this@MainActivity, getString(R.string.convenience_hold)) { prefs.convenienceHold = it; ui.refresh() }
        }
        clickRow(getString(R.string.convenience_double), { actionLabel(this@MainActivity, prefs.convenienceDouble) }) {
            ActionPicker.show(this@MainActivity, getString(R.string.convenience_double)) { prefs.convenienceDouble = it; ui.refresh() }
        }
        clickRow(getString(R.string.convenience_key_code), {
            KeyEvent.keyCodeToString(prefs.convenienceKeyCode).removePrefix("KEYCODE_")
        }) { learn(KeyTesterActivity.LEARN_CONVENIENCE) }

        // ------------------------------------------------------------- predictions
        section(getString(R.string.section_keyboard))
        note { getString(R.string.keyboard_explain) }
        switchRow(getString(R.string.ime_swipe_select), getString(R.string.ime_swipe_select_summary), { prefs.imeSwipeSelect }) {
            prefs.imeSwipeSelect = it
        }
        clickRow(getString(R.string.ime_horizontal), { horizontalLabel(prefs.imeHorizontal) }) { pickHorizontal() }
        seekRow(getString(R.string.swipe_distance), 1, 10, { prefs.imeSwipeLevel }, { prefs.imeSwipeLevel = it }) {
            getString(R.string.swipe_distance_value, it)
        }
        switchRow(getString(R.string.ime_next_word), "", { prefs.imeNextWord }) { prefs.imeNextWord = it }
        switchRow(getString(R.string.ime_double_space), "", { prefs.imeDoubleSpacePeriod }) { prefs.imeDoubleSpacePeriod = it }
        switchRow(getString(R.string.ime_learn), getString(R.string.ime_learn_summary), { prefs.imeLearn }) { prefs.imeLearn = it }
        switchRow(getString(R.string.invert_direction), getString(R.string.invert_direction_summary), { prefs.imeInvert }) {
            prefs.imeInvert = it
        }
        clickRow(getString(R.string.ime_forget), { "" }) { confirmForget() }

        // ------------------------------------------------------------- diagnostics
        section(getString(R.string.section_diagnostics))
        clickRow(getString(R.string.tester_title), { getString(R.string.tester_summary) }) {
            startActivity(Intent(this@MainActivity, KeyTesterActivity::class.java))
        }
        clickRow(getString(R.string.about_source), { getString(R.string.about_version, BuildConfig.VERSION_NAME) }) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.repo_url))))
        }
    }

    private fun keyboardStatus(): String {
        val id = ComponentName(this, PredictiveKeyboardService::class.java)
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.component == id }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it) } == id
        return when {
            current -> check(true) + getString(R.string.setup_keyboard_current)
            enabled -> check(false) + getString(R.string.setup_keyboard_enabled)
            else -> check(false) + getString(R.string.setup_keyboard_off)
        }
    }

    private fun onKeyboardRowClicked() {
        val id = ComponentName(this, PredictiveKeyboardService::class.java)
        val imm = getSystemService(InputMethodManager::class.java)
        if (imm.enabledInputMethodList.any { it.component == id }) imm.showInputMethodPicker()
        else open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    private fun learn(target: String) {
        startActivity(Intent(this, KeyTesterActivity::class.java).putExtra(KeyTesterActivity.EXTRA_LEARN, target))
    }

    private fun styleLabel(style: ReelsStyle) = getString(
        when (style) {
            ReelsStyle.SMART -> R.string.reels_style_smart
            ReelsStyle.PAGE -> R.string.reels_style_page
            ReelsStyle.DRAG -> R.string.reels_style_drag
        },
    )

    private fun pickReelsStyle() {
        val styles = ReelsStyle.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.reels_style)
            .setSingleChoiceItems(styles.map { styleLabel(it) }.toTypedArray(), styles.indexOf(prefs.reelsStyle)) { d, which ->
                prefs.reelsStyle = styles[which]
                d.dismiss()
                ui.refresh()
            }
            .show()
    }

    private fun horizontalLabel(mode: HorizontalSwipe) = getString(
        when (mode) {
            HorizontalSwipe.CURSOR -> R.string.ime_horizontal_cursor
            HorizontalSwipe.DELETE_WORD -> R.string.ime_horizontal_delete
            HorizontalSwipe.OFF -> R.string.ime_horizontal_off
        },
    )

    private fun pickHorizontal() {
        val modes = HorizontalSwipe.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.ime_horizontal)
            .setSingleChoiceItems(modes.map { horizontalLabel(it) }.toTypedArray(), modes.indexOf(prefs.imeHorizontal)) { d, which ->
                prefs.imeHorizontal = modes[which]
                d.dismiss()
                ui.refresh()
            }
            .show()
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ime_forget)
            .setMessage(R.string.ime_forget_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> prefs.requestForgetLearnedWords() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
