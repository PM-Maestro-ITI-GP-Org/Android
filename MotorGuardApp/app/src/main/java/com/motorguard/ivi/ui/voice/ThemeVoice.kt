package com.motorguard.ivi.ui.voice

import android.util.Log
import com.motorguard.ivi.ui.theme.ThemeMode
import com.motorguard.ivi.ui.theme.ThemeState

/**
 * Day, night, or let the car decide.
 *
 * The smallest of these handlers and the one with the clearest case for being spoken: reaching
 * Settings, finding the theme pane and tapping a mode is four interactions to stop the screen
 * blinding someone who has just driven into a tunnel.
 *
 * [ThemeState] is an object that persists through [com.motorguard.ivi.data.LocalStore], so this
 * is the same switch the pane flips and the choice survives a restart either way.
 */
object ThemeVoice {

    private const val TAG = "MotorGuardVoice"

    fun handle(utterance: String): String? {
        val mode = intentOf(utterance) ?: return null
        Log.i(TAG, "theme command: $mode")
        if (ThemeState.mode == mode) {
            return when (mode) {
                ThemeMode.NIGHT -> "Already on night mode."
                ThemeMode.DAY -> "Already on day mode."
                ThemeMode.AUTO -> "Already switching automatically."
            }
        }
        ThemeState.mode = mode
        return when (mode) {
            ThemeMode.NIGHT -> "Night mode on."
            ThemeMode.DAY -> "Day mode on."
            ThemeMode.AUTO -> "I'll switch with the time of day."
        }
    }

    /** Keyword only: it changes the screen, so it acts. */
    internal fun intentOf(utterance: String): ThemeMode? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null
        if (AUTO.any { text.contains(it) }) return ThemeMode.AUTO
        if (NIGHT.any { text.contains(it) }) return ThemeMode.NIGHT
        if (DAY.any { text.contains(it) }) return ThemeMode.DAY
        return null
    }

    private val AUTO = listOf(
        "automatic theme", "auto theme", "decide the theme", "switch automatically",
        "automatic brightness", "theme on auto",
    )
    private val NIGHT = listOf(
        "night mode", "dark mode", "make it darker", "make the screen darker",
        "darker screen", "too bright", "night theme", "dim the screen",
    )
    private val DAY = listOf(
        "day mode", "light mode", "make it brighter", "make the screen brighter",
        "brighter screen", "too dark", "day theme", "light theme",
    )

    private fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
