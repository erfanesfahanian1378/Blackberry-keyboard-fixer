package io.github.erfanesfahanian.key2fixer.keys

/** Things a single key can do while a Reels / Shorts / TikTok video is on screen. */
enum class VideoAction(val code: String) {
    NONE("none"),
    PLAY_PAUSE("play_pause"),
    LIKE("like"),
    SPEED_2X("speed_2x"),
    COMMENTS("comments"),
    REPOST("repost"),
    SHARE("share"),
    SAVE("save"),
    NEXT_VIDEO("next"),
    PREVIOUS_VIDEO("previous");

    /** Actions that press a button in the app rather than playing a touch gesture. */
    val pressesButton: Boolean get() = this == COMMENTS || this == REPOST || this == SHARE || this == SAVE

    companion object {
        fun fromCode(code: String?): VideoAction = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** Which keys can carry a video shortcut, and the defaults. */
object VideoKeys {
    const val SPACE = "SPACE"

    /** All key slots in display order: A..Z, then Space. */
    val SLOTS: List<String> = ('A'..'Z').map { it.toString() } + SPACE

    val DEFAULTS: Map<String, VideoAction> = mapOf(
        "P" to VideoAction.SPEED_2X,
        "C" to VideoAction.COMMENTS,
        "R" to VideoAction.REPOST,
        "L" to VideoAction.LIKE,
        "S" to VideoAction.SHARE,
        SPACE to VideoAction.PLAY_PAUSE,
    )

    val DEFAULT_DOUBLE_TAP = VideoAction.LIKE

    /** The slot for an Android key code, or null if the key can't carry a video shortcut. */
    fun slotFor(keyCode: Int): String? = when {
        keyCode == KeyCodes.SPACE -> SPACE
        else -> KeyCodes.letterOf(keyCode)?.toString()
    }
}
