package io.github.erfanesfahanian.key2fixer.ui

import android.app.Activity
import android.os.Bundle
import io.github.erfanesfahanian.key2fixer.keys.ConvenienceFallback

/**
 * Invisible "digital assistant" entry point for the Convenience key.
 *
 * LineageOS sends the KEY2 Convenience key as ASSIST: a press launches the default assistant
 * (ACTION_ASSIST), a long press starts ACTION_VOICE_ASSIST. Choosing KEY2 Keyboard Fixer as the
 * digital assistant routes both here, which works even on builds where the key event itself
 * never reaches apps. See [ConvenienceFallback].
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConvenienceFallback.onSystemAssist(applicationContext, longPress = intent?.action == ACTION_VOICE_ASSIST)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        /** Intent.ACTION_VOICE_ASSIST is hidden from the SDK; this is the value the system sends. */
        const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"
    }
}
