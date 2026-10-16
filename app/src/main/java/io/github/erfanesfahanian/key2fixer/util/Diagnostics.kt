package io.github.erfanesfahanian.key2fixer.util

import android.os.Handler
import io.github.erfanesfahanian.key2fixer.keys.Scheduler

/** Shared state between the diagnostics screen and the services. */
object Diagnostics {
    /** While the key tester is open, the services let every key and touch through untouched. */
    @Volatile
    var keyTesterActive = false

    /** Last time (uptime ms) the accessibility service handled the Convenience key. */
    @Volatile
    var lastConvenienceHandled = 0L

    /** How the Convenience key was last seen, for the main screen and the key tester. */
    enum class ConvenienceRoute { KEY_EVENT, ASSIST_PRESS, ASSIST_HOLD }

    @Volatile
    var lastConvenienceRoute: ConvenienceRoute? = null

    /** Wall-clock time of [lastConvenienceRoute]. */
    @Volatile
    var lastConvenienceWallTime = 0L

    /** Increases on every sighting so screens can tell new ones apart. */
    @Volatile
    var convenienceSightings = 0

    fun recordConvenience(route: ConvenienceRoute) {
        lastConvenienceRoute = route
        lastConvenienceWallTime = System.currentTimeMillis()
        convenienceSightings++
    }

    /** Short human-readable status of the Reels interception, shown on the main screen. */
    @Volatile
    var reelsStatus: String = ""
}

/** [Scheduler] backed by an Android [Handler]. */
class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun postDelayed(delayMillis: Long, task: () -> Unit): Any {
        val r = Runnable(task)
        handler.postDelayed(r, delayMillis)
        return r
    }

    override fun cancel(token: Any) {
        handler.removeCallbacks(token as Runnable)
    }
}
