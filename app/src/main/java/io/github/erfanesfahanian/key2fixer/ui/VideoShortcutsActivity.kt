package io.github.erfanesfahanian.key2fixer.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import io.github.erfanesfahanian.key2fixer.R
import io.github.erfanesfahanian.key2fixer.keys.VideoAction
import io.github.erfanesfahanian.key2fixer.keys.VideoKeys
import io.github.erfanesfahanian.key2fixer.util.Prefs

/** Single-key shortcuts while a Reel / Short / TikTok is on screen, plus the keyboard double tap. */
class VideoShortcutsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.video_title)
        prefs = Prefs(this)
        ui = Ui(this)
        ui.note { getString(R.string.video_explain) }
        ui.switchRow(getString(R.string.video_enable), "", { prefs.videoShortcutsEnabled }) { prefs.videoShortcutsEnabled = it }
        ui.clickRow(getString(R.string.video_double_tap), { videoActionLabel(this, prefs.videoDoubleTap) }) {
            pick(getString(R.string.video_double_tap), allowHold = false) { prefs.videoDoubleTap = it }
        }
        ui.section(getString(R.string.video_keys))
        for (slot in VideoKeys.SLOTS) {
            val name = if (slot == VideoKeys.SPACE) getString(R.string.video_key_space) else slot
            ui.clickRow(getString(R.string.video_key, name), { videoActionLabel(this, prefs.videoAction(slot)) }) {
                pick(getString(R.string.video_key, name), allowHold = true) { prefs.setVideoAction(slot, it) }
            }
        }
        ui.install()
    }

    override fun onResume() {
        super.onResume()
        ui.refresh()
    }

    private fun pick(title: String, allowHold: Boolean, onPicked: (VideoAction) -> Unit) {
        // 2x speed lasts while a key is held, so it can't be put on the double tap.
        val actions = VideoAction.entries.filter { allowHold || it != VideoAction.SPEED_2X }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { videoActionLabel(this, it) }.toTypedArray()) { _, which ->
                onPicked(actions[which])
                ui.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
