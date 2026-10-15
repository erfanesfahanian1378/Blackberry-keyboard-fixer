package io.github.erfanesfahanian.key2fixer.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import io.github.erfanesfahanian.key2fixer.R
import io.github.erfanesfahanian.key2fixer.keys.KeyAction
import io.github.erfanesfahanian.key2fixer.util.Prefs

/** Speed Key + letter shortcuts: a press and a hold action for every letter, A to Z. */
class ShortcutsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.speed_shortcuts)
        prefs = Prefs(this)
        ui = Ui(this)
        ui.note { getString(R.string.shortcuts_explain) }
        for (letter in 'A'..'Z') {
            ui.clickRow(getString(R.string.shortcut_letter, letter), { summary(letter) }) { edit(letter) }
        }
        ui.install()
    }

    override fun onResume() {
        super.onResume()
        ui.refresh()
    }

    private fun summary(letter: Char): String {
        val tap = prefs.letterAction(letter, false)
        val hold = prefs.letterAction(letter, true)
        if (tap == KeyAction.None && hold == KeyAction.None) return getString(R.string.shortcut_unassigned)
        return getString(R.string.shortcut_summary, actionLabel(this, tap), actionLabel(this, hold))
    }

    private fun edit(letter: Char) {
        val options = arrayOf(getString(R.string.shortcut_edit_press), getString(R.string.shortcut_edit_hold))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.shortcut_letter, letter))
            .setItems(options) { _, which ->
                val hold = which == 1
                ActionPicker.show(this, options[which]) {
                    prefs.setLetterAction(letter, hold, it)
                    ui.refresh()
                }
            }
            .show()
    }
}
