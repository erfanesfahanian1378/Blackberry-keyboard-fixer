package io.github.erfanesfahanian.key2fixer.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Finds an app's own on-screen button (Comment, Repost, Share, Save...) by its accessibility
 * label or view id, so a key can press it. Video apps put these in a column on the right, which
 * is preferred; text fields ("Add a comment...") and large containers are never chosen.
 */
object ButtonFinder {
    private const val MAX_NODES = 700

    fun findAndClick(root: AccessibilityNodeInfo?, keywords: List<String>, screenWidth: Int, screenHeight: Int): Boolean {
        val target = find(root, keywords, screenWidth, screenHeight) ?: return false
        var node: AccessibilityNodeInfo? = target
        // The label is often on an icon inside the clickable button.
        var depth = 0
        while (node != null && !node.isClickable && depth < 4) {
            node = node.parent
            depth++
        }
        return (node ?: target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun find(root: AccessibilityNodeInfo?, keywords: List<String>, screenWidth: Int, screenHeight: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val bounds = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        val maxArea = screenWidth.toLong() * screenHeight / 6
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.poll() ?: break
            visited++
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            if (!node.isVisibleToUser || node.isEditable) continue
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("EditText")) continue
            if (!matches(node, keywords)) continue
            node.getBoundsInScreen(bounds)
            val area = bounds.width().toLong() * bounds.height()
            if (area <= 0 || area > maxArea) continue
            // Prefer the right-hand action column, then smaller (more button-like) nodes.
            var score = 0
            if (bounds.centerX() > screenWidth * 0.6f) score += 1000
            if (node.isClickable) score += 200
            score -= (area / 1000).toInt().coerceAtMost(500)
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    private fun matches(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val label = buildString {
            node.contentDescription?.let { append(it).append(' ') }
            node.text?.let { if (it.length <= 40) append(it).append(' ') } // ignore long captions
            node.viewIdResourceName?.let { append(it.substringAfter('/')) }
        }.lowercase()
        if (label.isBlank()) return false
        return keywords.any { label.contains(it) }
    }
}
