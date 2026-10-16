package io.github.erfanesfahanian.key2fixer.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.erfanesfahanian.key2fixer.R
import io.github.erfanesfahanian.key2fixer.keys.BuiltinAction
import io.github.erfanesfahanian.key2fixer.keys.KeyAction
import io.github.erfanesfahanian.key2fixer.util.Diagnostics
import java.util.concurrent.Executors

/** An installed app that can be launched. */
data class AppEntry(val packageName: String, val label: String, val icon: Drawable?)

object Apps {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun loadLaunchable(context: Context, done: (List<AppEntry>) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val pm = app.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(intent, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != app.packageName }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .sortedBy { it.label.lowercase() }
            main.post { done(list) }
        }
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }
}

fun builtinLabel(context: Context, kind: BuiltinAction): String = context.getString(
    when (kind) {
        BuiltinAction.COPY -> R.string.action_copy
        BuiltinAction.CUT -> R.string.action_cut
        BuiltinAction.PASTE -> R.string.action_paste
        BuiltinAction.SELECT_ALL -> R.string.action_select_all
        BuiltinAction.CLIPBOARD -> R.string.action_clipboard
        BuiltinAction.BACK -> R.string.action_back
        BuiltinAction.HOME -> R.string.action_home
        BuiltinAction.RECENTS -> R.string.action_recents
        BuiltinAction.NOTIFICATIONS -> R.string.action_notifications
        BuiltinAction.QUICK_SETTINGS -> R.string.action_quick_settings
        BuiltinAction.POWER_MENU -> R.string.action_power_menu
        BuiltinAction.LOCK_SCREEN -> R.string.action_lock_screen
        BuiltinAction.SCREENSHOT -> R.string.action_screenshot
        BuiltinAction.SPLIT_SCREEN -> R.string.action_split_screen
        BuiltinAction.TORCH -> R.string.action_torch
        BuiltinAction.CAMERA -> R.string.action_camera
        BuiltinAction.ASSISTANT -> R.string.action_assistant
        BuiltinAction.MEDIA_PLAY_PAUSE -> R.string.action_media_play_pause
        BuiltinAction.MEDIA_NEXT -> R.string.action_media_next
        BuiltinAction.MEDIA_PREVIOUS -> R.string.action_media_previous
        BuiltinAction.VOLUME_PANEL -> R.string.action_volume_panel
        BuiltinAction.TOGGLE_REELS -> R.string.action_toggle_reels
        BuiltinAction.OPEN_SETTINGS -> R.string.action_open_settings
    },
)

fun routeLabel(context: Context, route: Diagnostics.ConvenienceRoute): String = context.getString(
    when (route) {
        Diagnostics.ConvenienceRoute.KEY_EVENT -> R.string.route_key_event
        Diagnostics.ConvenienceRoute.ASSIST_PRESS -> R.string.route_assist_press
        Diagnostics.ConvenienceRoute.ASSIST_HOLD -> R.string.route_assist_hold
    },
)

fun actionLabel(context: Context, action: KeyAction): String = when (action) {
    KeyAction.None -> context.getString(R.string.action_none)
    is KeyAction.Launch -> context.getString(R.string.action_open_app_named, Apps.label(context, action.packageName))
    is KeyAction.Builtin -> builtinLabel(context, action.kind)
}

object ActionPicker {
    fun show(activity: Activity, title: String, onPicked: (KeyAction) -> Unit) {
        val builtins = BuiltinAction.entries
        val labels = listOf(
            activity.getString(R.string.action_none),
            activity.getString(R.string.action_open_app),
        ) + builtins.map { builtinLabel(activity, it) }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> onPicked(KeyAction.None)
                    1 -> AppPicker.show(activity) { onPicked(KeyAction.Launch(it)) }
                    else -> onPicked(KeyAction.Builtin(builtins[which - 2]))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

object AppPicker {
    fun show(activity: Activity, onPicked: (String) -> Unit) {
        Apps.loadLaunchable(activity) { apps ->
            if (activity.isFinishing) return@loadLaunchable
            AlertDialog.Builder(activity)
                .setTitle(R.string.pick_app)
                .setAdapter(AppAdapter(activity, apps, null)) { _, which -> onPicked(apps[which].packageName) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}

/** List rows with an app icon, label and (optionally) a check mark. */
class AppAdapter(
    private val context: Context,
    private val apps: List<AppEntry>,
    private val checked: Set<String>?,
) : BaseAdapter() {
    override fun getCount() = apps.size
    override fun getItem(position: Int) = apps[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = Ui.dp(context, 12)
            setPadding(pad * 2, pad, pad * 2, pad)
            addView(ImageView(context), LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36)))
            addView(TextView(context).apply {
                textSize = 16f
                setPadding(Ui.dp(context, 16), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply { textSize = 18f })
        }
        val app = apps[position]
        (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
        (row.getChildAt(1) as TextView).text = app.label
        (row.getChildAt(2) as TextView).text = if (checked?.contains(app.packageName) == true) "✓" else ""
        return row
    }
}
