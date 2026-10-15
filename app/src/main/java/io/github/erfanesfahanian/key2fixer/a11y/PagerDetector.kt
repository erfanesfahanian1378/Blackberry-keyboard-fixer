package io.github.erfanesfahanian.key2fixer.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Guesses whether the screen shows a full-screen, one-item-per-page vertical pager (Instagram
 * Reels, YouTube Shorts, TikTok) rather than a normal scrolling feed.
 */
object PagerDetector {
    private const val MAX_NODES = 350

    fun isFullScreenPager(root: AccessibilityNodeInfo?, screenWidth: Int, screenHeight: Int): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val bounds = Rect()
        val childBounds = Rect()
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.poll() ?: break
            visited++
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(bounds)
            val big = bounds.height() >= screenHeight * 0.55f && bounds.width() >= screenWidth * 0.8f
            val cls = node.className?.toString().orEmpty()
            if (big && cls.contains("ViewPager")) return true
            if (big && node.isScrollable) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (!child.isVisibleToUser) continue
                    child.getBoundsInScreen(childBounds)
                    if (childBounds.height() >= bounds.height() * 0.85f && childBounds.width() >= bounds.width() * 0.85f) {
                        return true
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return false
    }
}
