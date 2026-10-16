package io.github.erfanesfahanian.key2fixer.keys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.erfanesfahanian.key2fixer.a11y.KeyboardFixerService
import io.github.erfanesfahanian.key2fixer.util.Diagnostics
import io.github.erfanesfahanian.key2fixer.util.Prefs

/**
 * The Convenience key through the system's assistant route.
 *
 * LineageOS turns the KEY2 Convenience key into an ASSIST key. On some builds the key event
 * never reaches apps or accessibility services, but the system still reacts to it: a press
 * launches the default digital assistant (ACTION_ASSIST) and a long press starts
 * ACTION_VOICE_ASSIST. With KEY2 Keyboard Fixer chosen as the digital assistant, both land in
 * [io.github.erfanesfahanian.key2fixer.ui.AssistActivity], which calls this.
 */
object ConvenienceFallback {
    private const val DOUBLE_PRESS_MILLIS = 400L
    private const val SERVICE_HANDLED_WINDOW_MILLIS = 2000L

    private val handler = Handler(Looper.getMainLooper())
    private var pendingPress: Runnable? = null

    fun onSystemAssist(context: Context, longPress: Boolean) {
        Diagnostics.recordConvenience(
            if (longPress) Diagnostics.ConvenienceRoute.ASSIST_HOLD else Diagnostics.ConvenienceRoute.ASSIST_PRESS,
        )
        // The key tester only reports what arrived; nothing runs while it is open.
        if (Diagnostics.keyTesterActive) return
        // The accessibility service already saw the key itself and ran the action.
        if (SystemClock.uptimeMillis() - Diagnostics.lastConvenienceHandled < SERVICE_HANDLED_WINDOW_MILLIS) return
        val prefs = Prefs(context)
        if (!prefs.convenienceEnabled) return

        if (longPress) {
            cancelPendingPress()
            run(context, prefs.convenienceHold.takeIf { it != KeyAction.None } ?: prefs.convenienceTap)
            return
        }
        val double = prefs.convenienceDouble
        if (double == KeyAction.None) {
            run(context, prefs.convenienceTap)
            return
        }
        if (pendingPress != null) {
            cancelPendingPress()
            run(context, double)
        } else {
            val press = Runnable {
                pendingPress = null
                run(context, Prefs(context).convenienceTap)
            }
            pendingPress = press
            handler.postDelayed(press, DOUBLE_PRESS_MILLIS)
        }
    }

    private fun cancelPendingPress() {
        pendingPress?.let(handler::removeCallbacks)
        pendingPress = null
    }

    private fun run(context: Context, action: KeyAction) {
        val service = KeyboardFixerService.instance
        if (service != null) service.runAction(action) else ActionRunner(context.applicationContext, null).run(action)
    }
}
