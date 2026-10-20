package io.github.erfanesfahanian.key2fixer.keys

/** Delayed-task abstraction so the router can run on a Handler and in plain unit tests. */
interface Scheduler {
    fun postDelayed(delayMillis: Long, task: () -> Unit): Any
    fun cancel(token: Any)
}

/** The parts of a KeyEvent the router needs. */
data class KeyInput(val isDown: Boolean, val keyCode: Int, val repeatCount: Int, val isExternal: Boolean = false)

/** Snapshot of the user's key settings. */
data class KeyConfig(
    val speedEnabled: Boolean = true,
    val speedKeyCodes: Set<Int> = DEFAULT_SPEED_KEYS,
    /** Run when the Speed Key is tapped on its own. */
    val speedTapAction: KeyAction = KeyAction.None,
    /** Let the Speed Key reach the keyboard app while a text field is focused (e.g. as Ctrl). */
    val speedPassThroughWhileTyping: Boolean = false,
    val letterTap: Map<Char, KeyAction> = emptyMap(),
    val letterHold: Map<Char, KeyAction> = emptyMap(),
    val holdMillis: Long = 450,
    val convenienceEnabled: Boolean = true,
    val convenienceKeyCode: Int = KeyCodes.ASSIST,
    val convenienceTap: KeyAction = KeyAction.None,
    val convenienceHold: KeyAction = KeyAction.None,
    val convenienceDouble: KeyAction = KeyAction.None,
    val doubleTapMillis: Long = 300,
) {
    companion object {
        /** On LineageOS the KEY2 Speed Key reports as right Shift or Fn depending on the build. */
        val DEFAULT_SPEED_KEYS = setOf(KeyCodes.SHIFT_RIGHT, KeyCodes.FUNCTION)
    }
}

/** Android KeyEvent codes used here (values are part of the public API and never change). */
object KeyCodes {
    const val A = 29
    const val Z = 54
    const val SPACE = 62
    const val SHIFT_RIGHT = 60
    const val FUNCTION = 119
    const val CTRL_LEFT = 113
    const val CTRL_RIGHT = 114
    const val ASSIST = 219

    fun letterOf(keyCode: Int): Char? = if (keyCode in A..Z) 'A' + (keyCode - A) else null
}

/**
 * Speed Key + letter shortcuts and Convenience-key press/hold/double-press, as a state machine.
 * [onKey] returns true when the key was consumed and must not reach the app.
 */
class KeyRouter(
    private val scheduler: Scheduler,
    private val perform: (KeyAction) -> Unit,
    private val onUnassignedLetter: (Char) -> Unit = {},
) {
    // Speed Key state
    private var speedDown = false
    private var speedUsedAsModifier = false
    private var speedPassingThrough = false

    // Letter pressed with the Speed Key
    private var pendingLetterCode = -1
    private var letterHoldFired = false
    private var letterHoldToken: Any? = null

    // Convenience key state
    private var convDown = false
    private var convHoldFired = false
    private var convHoldToken: Any? = null
    private var convSingleToken: Any? = null

    fun onKey(key: KeyInput, config: KeyConfig, isTyping: Boolean): Boolean {
        if (key.isExternal) return false

        if (config.convenienceEnabled && key.keyCode == config.convenienceKeyCode) {
            return onConvenience(key, config)
        }
        // A letter whose down event was taken as a shortcut keeps being swallowed until it
        // is released, even if the Speed Key was let go first.
        if (key.keyCode == pendingLetterCode) return onShortcutLetter(key, config)

        if (!config.speedEnabled) return false
        if (key.keyCode in config.speedKeyCodes) return onSpeed(key, config, isTyping)

        if (speedDown && !speedPassingThrough && key.isDown) {
            val letter = KeyCodes.letterOf(key.keyCode)
            if (letter != null) {
                speedUsedAsModifier = true
                return onShortcutLetter(key, config)
            }
            // Other keys keep working while the Speed Key is held.
            speedUsedAsModifier = true
        }
        return false
    }

    private fun onSpeed(key: KeyInput, config: KeyConfig, isTyping: Boolean): Boolean {
        if (key.isDown) {
            if (key.repeatCount == 0) {
                speedDown = true
                speedUsedAsModifier = false
                speedPassingThrough = isTyping && config.speedPassThroughWhileTyping
            }
            return !speedPassingThrough
        }
        val passed = speedPassingThrough
        if (speedDown && !speedUsedAsModifier && !passed && config.speedTapAction != KeyAction.None) {
            perform(config.speedTapAction)
        }
        speedDown = false
        speedPassingThrough = false
        return !passed
    }

    private fun onShortcutLetter(key: KeyInput, config: KeyConfig): Boolean {
        val letter = KeyCodes.letterOf(key.keyCode) ?: return false
        val tap = config.letterTap[letter] ?: KeyAction.None
        val hold = config.letterHold[letter] ?: KeyAction.None
        if (key.isDown) {
            if (key.repeatCount > 0 || pendingLetterCode == key.keyCode) return true
            pendingLetterCode = key.keyCode
            letterHoldFired = false
            when {
                tap == KeyAction.None && hold == KeyAction.None -> {
                    letterHoldFired = true // nothing more to do on release
                    onUnassignedLetter(letter)
                }
                hold == KeyAction.None -> {
                    letterHoldFired = true
                    perform(tap) // no hold action: react immediately
                }
                else -> letterHoldToken = scheduler.postDelayed(config.holdMillis) {
                    letterHoldToken = null
                    letterHoldFired = true
                    perform(hold)
                }
            }
            return true
        }
        // Release
        letterHoldToken?.let { scheduler.cancel(it) }
        letterHoldToken = null
        if (!letterHoldFired && tap != KeyAction.None) perform(tap)
        pendingLetterCode = -1
        letterHoldFired = false
        return true
    }

    private fun onConvenience(key: KeyInput, config: KeyConfig): Boolean {
        val tap = config.convenienceTap
        val hold = config.convenienceHold
        val double = config.convenienceDouble
        if (key.isDown) {
            if (key.repeatCount > 0 || convDown) return true
            convDown = true
            convHoldFired = false
            if (hold == KeyAction.None && double == KeyAction.None) {
                convHoldFired = true // react on press for snappiness
                if (tap != KeyAction.None) perform(tap)
                return true
            }
            if (hold != KeyAction.None) {
                convHoldToken = scheduler.postDelayed(config.holdMillis) {
                    convHoldToken = null
                    convHoldFired = true
                    convSingleToken?.let { scheduler.cancel(it) }
                    convSingleToken = null
                    perform(hold)
                }
            }
            return true
        }
        // Release
        convDown = false
        convHoldToken?.let { scheduler.cancel(it) }
        convHoldToken = null
        if (convHoldFired) return true
        if (double == KeyAction.None) {
            if (tap != KeyAction.None) perform(tap)
            return true
        }
        val pendingSingle = convSingleToken
        if (pendingSingle != null) {
            scheduler.cancel(pendingSingle)
            convSingleToken = null
            perform(double)
        } else {
            convSingleToken = scheduler.postDelayed(config.doubleTapMillis) {
                convSingleToken = null
                if (tap != KeyAction.None) perform(tap)
            }
        }
        return true
    }

    /** Forgets held keys, e.g. when the screen turns off or settings change. */
    fun reset() {
        letterHoldToken?.let { scheduler.cancel(it) }
        convHoldToken?.let { scheduler.cancel(it) }
        convSingleToken?.let { scheduler.cancel(it) }
        letterHoldToken = null
        convHoldToken = null
        convSingleToken = null
        speedDown = false
        speedUsedAsModifier = false
        speedPassingThrough = false
        pendingLetterCode = -1
        letterHoldFired = false
        convDown = false
        convHoldFired = false
    }
}
