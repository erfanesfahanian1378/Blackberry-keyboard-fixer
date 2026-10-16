package io.github.erfanesfahanian.key2fixer.util

import android.content.Context
import android.content.SharedPreferences
import io.github.erfanesfahanian.key2fixer.keys.BuiltinAction
import io.github.erfanesfahanian.key2fixer.keys.KeyAction
import io.github.erfanesfahanian.key2fixer.keys.KeyCodes
import io.github.erfanesfahanian.key2fixer.keys.KeyConfig

/** All user settings, in one SharedPreferences file shared by the services and the UI. */
class Prefs(context: Context) {
    private val appContext: Context = context.applicationContext
    val sp: SharedPreferences = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Reels / Shorts paging ---
    var reelsEnabled: Boolean
        get() = sp.getBoolean("reels_enabled", true)
        set(v) = sp.edit().putBoolean("reels_enabled", v).apply()

    var reelsApps: Set<String>
        get() = sp.getStringSet("reels_apps", null)?.toSet() ?: DEFAULT_REELS_APPS
        set(v) = sp.edit().putStringSet("reels_apps", v).apply()

    var reelsStyle: ReelsStyle
        get() = ReelsStyle.fromCode(sp.getString("reels_style", null))
        set(v) = sp.edit().putString("reels_style", v.code).apply()

    /** 1 (short swipe is enough) .. 10 (long swipe needed). */
    var reelsSwipeLevel: Int
        get() = sp.getInt("reels_swipe_level", 5)
        set(v) = sp.edit().putInt("reels_swipe_level", v).apply()

    var reelsInvert: Boolean
        get() = sp.getBoolean("reels_invert", false)
        set(v) = sp.edit().putBoolean("reels_invert", v).apply()

    var reelsHorizontal: Boolean
        get() = sp.getBoolean("reels_horizontal", false)
        set(v) = sp.edit().putBoolean("reels_horizontal", v).apply()

    // --- Speed Key ---
    var speedEnabled: Boolean
        get() = sp.getBoolean("speed_enabled", true)
        set(v) = sp.edit().putBoolean("speed_enabled", v).apply()

    var speedKeyCodes: Set<Int>
        get() = sp.getStringSet("speed_keycodes", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: KeyConfig.DEFAULT_SPEED_KEYS
        set(v) = sp.edit().putStringSet("speed_keycodes", v.map { it.toString() }.toSet()).apply()

    var speedTapAction: KeyAction
        get() = KeyAction.decode(sp.getString("speed_tap", null))
        set(v) = sp.edit().putString("speed_tap", v.encode()).apply()

    var speedPassThroughWhileTyping: Boolean
        get() = sp.getBoolean("speed_passthrough_typing", false)
        set(v) = sp.edit().putBoolean("speed_passthrough_typing", v).apply()

    fun letterAction(letter: Char, hold: Boolean): KeyAction {
        val key = letterKey(letter, hold)
        if (!sp.contains(key)) {
            val defaults = if (hold) DEFAULT_LETTER_HOLD else DEFAULT_LETTER_TAP
            return defaults[letter.uppercaseChar()] ?: KeyAction.None
        }
        return KeyAction.decode(sp.getString(key, null))
    }

    fun setLetterAction(letter: Char, hold: Boolean, action: KeyAction) =
        sp.edit().putString(letterKey(letter, hold), action.encode()).apply()

    private fun letterKey(letter: Char, hold: Boolean) = "letter_${letter.uppercaseChar()}_${if (hold) "hold" else "tap"}"

    // --- Convenience key ---
    var convenienceEnabled: Boolean
        get() = sp.getBoolean("conv_enabled", true)
        set(v) = sp.edit().putBoolean("conv_enabled", v).apply()

    var convenienceKeyCode: Int
        get() = sp.getInt("conv_keycode", KeyCodes.ASSIST)
        set(v) = sp.edit().putInt("conv_keycode", v).apply()

    var convenienceTap: KeyAction
        get() = KeyAction.decode(sp.getString("conv_tap", BuiltinAction.TORCH.code))
        set(v) = sp.edit().putString("conv_tap", v.encode()).apply()

    var convenienceHold: KeyAction
        get() = KeyAction.decode(sp.getString("conv_hold", BuiltinAction.CAMERA.code))
        set(v) = sp.edit().putString("conv_hold", v.encode()).apply()

    var convenienceDouble: KeyAction
        get() = KeyAction.decode(sp.getString("conv_double", null))
        set(v) = sp.edit().putString("conv_double", v.encode()).apply()

    // --- Predictive keyboard ---
    var imeSwipeSelect: Boolean
        get() = sp.getBoolean("ime_swipe_select", true)
        set(v) = sp.edit().putBoolean("ime_swipe_select", v).apply()

    var imeHorizontal: HorizontalSwipe
        get() = HorizontalSwipe.fromCode(sp.getString("ime_horizontal", null))
        set(v) = sp.edit().putString("ime_horizontal", v.code).apply()

    var imeSwipeLevel: Int
        get() = sp.getInt("ime_swipe_level", 5)
        set(v) = sp.edit().putInt("ime_swipe_level", v).apply()

    var imeInvert: Boolean
        get() = sp.getBoolean("ime_invert", false)
        set(v) = sp.edit().putBoolean("ime_invert", v).apply()

    var imeDoubleSpacePeriod: Boolean
        get() = sp.getBoolean("ime_double_space", true)
        set(v) = sp.edit().putBoolean("ime_double_space", v).apply()

    var imeLearn: Boolean
        get() = sp.getBoolean("ime_learn", true)
        set(v) = sp.edit().putBoolean("ime_learn", v).apply()

    var imeNextWord: Boolean
        get() = sp.getBoolean("ime_next_word", true)
        set(v) = sp.edit().putBoolean("ime_next_word", v).apply()

    // --- Clipboard history ---
    var clipboardEnabled: Boolean
        get() = sp.getBoolean(KEY_CLIPBOARD_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_CLIPBOARD_ENABLED, v).apply()

    /** Deletes the clipboard history file and tells a running keyboard to drop its copy. */
    fun requestClearClipboard() {
        java.io.File(appContext.filesDir, CLIPBOARD_FILE).delete()
        sp.edit().putLong(KEY_CLEAR_CLIPBOARD, System.currentTimeMillis()).apply()
    }

    /**
     * Deletes the learned words. The keyboard may hold them in memory, so it also gets a signal
     * through [KEY_FORGET] to clear its copy.
     */
    fun requestForgetLearnedWords() {
        java.io.File(appContext.filesDir, USER_WORDS_FILE).delete()
        sp.edit().putLong(KEY_FORGET, System.currentTimeMillis()).apply()
    }

    fun keyConfig(): KeyConfig {
        val tap = HashMap<Char, KeyAction>()
        val hold = HashMap<Char, KeyAction>()
        for (c in 'A'..'Z') {
            letterAction(c, false).takeIf { it != KeyAction.None }?.let { tap[c] = it }
            letterAction(c, true).takeIf { it != KeyAction.None }?.let { hold[c] = it }
        }
        return KeyConfig(
            speedEnabled = speedEnabled,
            speedKeyCodes = speedKeyCodes,
            speedTapAction = speedTapAction,
            speedPassThroughWhileTyping = speedPassThroughWhileTyping,
            letterTap = tap,
            letterHold = hold,
            convenienceEnabled = convenienceEnabled,
            convenienceKeyCode = convenienceKeyCode,
            convenienceTap = convenienceTap,
            convenienceHold = convenienceHold,
            convenienceDouble = convenienceDouble,
        )
    }

    companion object {
        const val FILE = "key2fixer"
        const val USER_WORDS_FILE = "user_words.tsv"
        const val KEY_FORGET = "ime_forget_request"
        const val CLIPBOARD_FILE = "clipboard.tsv"
        const val KEY_CLIPBOARD_ENABLED = "clipboard_enabled"
        const val KEY_CLEAR_CLIPBOARD = "clipboard_clear_request"

        /** Speed Key + tap letter defaults: V opens the clipboard history (hold V still pastes). */
        val DEFAULT_LETTER_TAP: Map<Char, KeyAction> = mapOf(
            'V' to KeyAction.Builtin(BuiltinAction.CLIPBOARD),
        )

        /** Speed Key + hold letter defaults, like Ctrl shortcuts on a computer. */
        val DEFAULT_LETTER_HOLD: Map<Char, KeyAction> = mapOf(
            'C' to KeyAction.Builtin(BuiltinAction.COPY),
            'X' to KeyAction.Builtin(BuiltinAction.CUT),
            'V' to KeyAction.Builtin(BuiltinAction.PASTE),
            'A' to KeyAction.Builtin(BuiltinAction.SELECT_ALL),
        )

        /** Apps with full-screen vertical video feeds (Reels, Shorts, TikTok-style). */
        val DEFAULT_REELS_APPS = setOf(
            "com.instagram.android",
            "com.instagram.lite",
            "com.google.android.youtube",
            "app.revanced.android.youtube",
            "app.rvx.android.youtube",
            "com.zhiliaoapp.musically",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.trill",
            "com.snapchat.android",
            "com.facebook.katana",
        )
    }
}

enum class ReelsStyle(val code: String) {
    /** Full-screen video pagers flip one item per swipe; other screens scroll like a finger drag. */
    SMART("smart"),

    /** Every swipe flips one full page. */
    PAGE("page"),

    /** Every swipe is replayed as a finger drag of similar length and speed. */
    DRAG("drag");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: SMART
    }
}

enum class HorizontalSwipe(val code: String) {
    CURSOR("cursor"),
    DELETE_WORD("delete_word"),
    OFF("off");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: CURSOR
    }
}
