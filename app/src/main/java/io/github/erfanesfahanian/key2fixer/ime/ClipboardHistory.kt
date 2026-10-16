package io.github.erfanesfahanian.key2fixer.ime

import java.io.File
import java.util.Base64

/**
 * Recently copied texts, newest first, plus pinned ones that are kept until you unpin them.
 * Stored only on the phone, in the app's private files.
 */
class ClipboardHistory(
    private val file: File?,
    private val maxRecent: Int = 30,
    private val maxPinned: Int = 50,
) {
    data class Clip(val text: String, val time: Long, val pinned: Boolean)

    private val clips = ArrayList<Clip>() // newest first

    @Volatile
    var isDirty = false
        private set

    /** Records a copy. Copying the same text again moves it to the top. Returns false if ignored. */
    @Synchronized
    fun add(text: String, time: Long): Boolean {
        if (text.isBlank() || text.length > MAX_CHARS) return false
        val existing = clips.indexOfFirst { it.text == text }
        if (existing == 0) return false // already the newest clip
        val pinned = existing > 0 && clips[existing].pinned
        if (existing > 0) clips.removeAt(existing)
        clips.add(0, Clip(text, time, pinned))
        trim()
        isDirty = true
        return true
    }

    /** Pinned clips first, then the others; newest first within each group. */
    @Synchronized
    fun items(): List<Clip> = clips.filter { it.pinned } + clips.filter { !it.pinned }

    @Synchronized
    fun togglePin(text: String) {
        val i = clips.indexOfFirst { it.text == text }
        if (i < 0) return
        clips[i] = clips[i].copy(pinned = !clips[i].pinned)
        trim()
        isDirty = true
    }

    @Synchronized
    fun remove(text: String) {
        if (clips.removeAll { it.text == text }) isDirty = true
    }

    /** Removes everything except pinned clips. */
    @Synchronized
    fun clearUnpinned() {
        if (clips.removeAll { !it.pinned }) isDirty = true
    }

    @Synchronized
    fun clearAll() {
        clips.clear()
        isDirty = true
    }

    @Synchronized
    fun size(): Int = clips.size

    private fun trim() {
        dropOldest(pinned = false, keep = maxRecent)
        dropOldest(pinned = true, keep = maxPinned)
    }

    private fun dropOldest(pinned: Boolean, keep: Int) {
        var count = 0
        val it = clips.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (c.pinned != pinned) continue
            count++
            if (count > keep) it.remove()
        }
    }

    @Synchronized
    fun load() {
        val f = file ?: return
        if (!f.exists()) return
        clips.clear()
        val decoder = Base64.getDecoder()
        f.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@forEachLine
            val time = parts[1].toLongOrNull() ?: return@forEachLine
            val text = runCatching { String(decoder.decode(parts[2]), Charsets.UTF_8) }.getOrNull() ?: return@forEachLine
            clips.add(Clip(text, time, parts[0] == "P"))
        }
        isDirty = false
    }

    /** Writes the history atomically. Safe to call from a background thread. */
    fun save() {
        val f = file ?: return
        val text = synchronized(this) {
            if (!isDirty) return
            isDirty = false
            val encoder = Base64.getEncoder()
            buildString {
                for (c in clips) {
                    append(if (c.pinned) 'P' else 'U').append('\t').append(c.time).append('\t')
                    append(encoder.encodeToString(c.text.toByteArray(Charsets.UTF_8))).append('\n')
                }
            }
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        /** Very large copies (whole documents) are not kept. */
        const val MAX_CHARS = 20_000
    }
}
