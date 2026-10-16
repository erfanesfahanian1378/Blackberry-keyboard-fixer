package io.github.erfanesfahanian.key2fixer.keys

/** Something a key or shortcut can do. Stored in preferences as a short string. */
sealed interface KeyAction {
    fun encode(): String

    data object None : KeyAction {
        override fun encode() = "none"
    }

    data class Launch(val packageName: String) : KeyAction {
        override fun encode() = "app:$packageName"
    }

    data class Builtin(val kind: BuiltinAction) : KeyAction {
        override fun encode() = kind.code
    }

    companion object {
        fun decode(raw: String?): KeyAction = when {
            raw.isNullOrBlank() || raw == "none" -> None
            raw.startsWith("app:") && raw.length > 4 -> Launch(raw.substring(4))
            else -> BuiltinAction.fromCode(raw)?.let { Builtin(it) } ?: None
        }
    }
}

/** Actions that do not need an app picked. Listed in this order in the action picker. */
enum class BuiltinAction(val code: String) {
    COPY("copy"),
    CUT("cut"),
    PASTE("paste"),
    SELECT_ALL("select_all"),
    BACK("back"),
    HOME("home"),
    RECENTS("recents"),
    NOTIFICATIONS("notifications"),
    QUICK_SETTINGS("quick_settings"),
    POWER_MENU("power_menu"),
    LOCK_SCREEN("lock_screen"),
    SCREENSHOT("screenshot"),
    SPLIT_SCREEN("split_screen"),
    TORCH("torch"),
    CAMERA("camera"),
    ASSISTANT("assistant"),
    MEDIA_PLAY_PAUSE("media_play_pause"),
    MEDIA_NEXT("media_next"),
    MEDIA_PREVIOUS("media_previous"),
    VOLUME_PANEL("volume_panel"),
    TOGGLE_REELS("toggle_reels"),
    OPEN_SETTINGS("open_settings");

    companion object {
        fun fromCode(code: String): BuiltinAction? = entries.firstOrNull { it.code == code }
    }
}
