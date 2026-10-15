package io.github.erfanesfahanian.key2fixer.ui

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import io.github.erfanesfahanian.key2fixer.a11y.KeyboardFixerService
import io.github.erfanesfahanian.key2fixer.keys.ActionRunner
import io.github.erfanesfahanian.key2fixer.util.Diagnostics
import io.github.erfanesfahanian.key2fixer.util.Prefs

/**
 * Invisible "assistant" activity.
 *
 * On LineageOS the KEY2 Convenience key is an ASSIST key, which always launches the default
 * digital assistant. If you pick KEY2 Keyboard Fixer as that assistant, the system lands here:
 * when the accessibility service already handled the press this closes immediately, otherwise
 * it runs the Convenience-key action itself so the key keeps working.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handledRecently = SystemClock.uptimeMillis() - Diagnostics.lastConvenienceHandled < 2000
        if (!handledRecently) {
            val action = Prefs(this).convenienceTap
            val service = KeyboardFixerService.instance
            if (service != null) service.runAction(action) else ActionRunner(applicationContext, null).run(action)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
