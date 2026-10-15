package io.github.erfanesfahanian.key2fixer.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ListView
import android.widget.TextView
import io.github.erfanesfahanian.key2fixer.R
import io.github.erfanesfahanian.key2fixer.util.Prefs

/** Choose the apps where keyboard swipes flip videos instead of mouse-wheel scrolling. */
class ReelsAppsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.reels_apps)
        prefs = Prefs(this)
        list = ListView(this).apply { fitsSystemWindows = true }
        list.addHeaderView(TextView(this).apply {
            text = getString(R.string.reels_apps_explain)
            val p = Ui.dp(this@ReelsAppsActivity, 20)
            setPadding(p, p, p, p / 2)
        }, null, false)
        setContentView(list)
        Apps.loadLaunchable(this) { apps ->
            val selected = prefs.reelsApps.toMutableSet()
            // Selected apps first, then everything else alphabetically.
            val ordered = apps.sortedBy { if (it.packageName in selected) 0 else 1 }
            val adapter = AppAdapter(this, ordered, selected)
            list.adapter = adapter
            list.setOnItemClickListener { _, _, position, _ ->
                val app = ordered[position - list.headerViewsCount]
                if (!selected.remove(app.packageName)) selected.add(app.packageName)
                prefs.reelsApps = selected.toSet()
                adapter.notifyDataSetChanged()
            }
        }
    }
}
