package com.motorguard.ivi.ui.voice

import android.util.Log
import com.motorguard.ivi.data.nav.NavFormat
import com.motorguard.ivi.ui.nav.NavPhase
import com.motorguard.ivi.ui.nav.NavSession
import com.motorguard.ivi.ui.nav.NavUiState

/**
 * How far, how long, and stop.
 *
 * "How long until we arrive" is the question a driver asks most and the one routing answered
 * worst: it opened the map so they could read a figure the app had already computed. The number
 * is in [com.motorguard.ivi.data.nav.NavProgress] either way — this says it instead of drawing it.
 *
 * Formatted through [NavFormat], the same helper the guidance card uses, so the spoken figure and
 * the one on screen are the same string and not two roundings of the same double.
 *
 * Setting a destination is deliberately not here. That needs a search, a result to be chosen and
 * a route confirmed, and picking the first hit on the driver's behalf is how an assistant sends
 * someone to the wrong town with the same confidence it would have shown for the right one.
 */
object NavVoice {

    private const val TAG = "MotorGuardVoice"

    internal enum class Ask { ETA, DISTANCE, CANCEL }

    fun handle(utterance: String): String? {
        val ask = intentOf(utterance) ?: return null
        Log.i(TAG, "nav command: $ask")
        val state = NavSession.state.value
        if (ask == Ask.CANCEL) {
            if (state.phase !is NavPhase.Guiding) return "There's no route to cancel."
            NavSession.endGuidance()
            return "Route cancelled."
        }
        return compose(ask, state)
    }

    /**
     * Trip progress regardless of wording, for the embedding matcher. Reads only; [Ask.CANCEL] is
     * deliberately unreachable this way.
     */
    fun answerProgress(): String = compose(Ask.ETA, NavSession.state.value)

    /**
     * Keyword only for [Ask.CANCEL] — it ends something the driver is relying on, and a misheard
     * command that strands someone is not a class of error worth trading for looser phrasing.
     * The two questions are safe enough that the embedding anchors carry them as well.
     */
    internal fun intentOf(utterance: String): Ask? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null
        if (CANCEL.any { text.contains(it) }) return Ask.CANCEL
        if (DISTANCE.any { text.contains(it) }) return Ask.DISTANCE
        if (ETA.any { text.contains(it) }) return Ask.ETA
        return null
    }

    private val CANCEL = listOf(
        "cancel the route", "cancel route", "cancel the navigation", "cancel navigation",
        "stop the navigation", "stop navigating", "stop guidance", "end the route",
        "forget the route", "clear the route",
    )
    private val DISTANCE = listOf(
        "how far", "how many kilometres", "how many kilometers", "how many miles",
        "distance to go", "distance left",
    )
    private val ETA = listOf(
        "how long until we", "how long till we", "how long to go", "when do we arrive",
        "when will we arrive", "when will we get there", "what time will we get there",
        "eta", "how much longer", "are we nearly there", "are we there yet",
    )

    /**
     * Pure, so every phase has a sentence pinned by a test rather than a drive.
     *
     * The distinction that matters is [NavPhase.Guiding] with a null `progress` — routing has
     * started but no position has landed yet, which is a real state lasting a second or two and
     * not the same as having no route at all.
     */
    internal fun compose(ask: Ask, state: NavUiState): String {
        val phase = state.phase as? NavPhase.Guiding
            ?: return "You're not navigating anywhere at the moment."
        val progress = phase.progress
            ?: return "The route's just started — I'll have a figure once we're moving."
        if (progress.arrived) return "You've arrived."

        return when (ask) {
            Ask.DISTANCE ->
                "${NavFormat.distance(progress.remainingDistanceMeters)} to go."
            Ask.ETA ->
                "${NavFormat.duration(progress.remainingDurationSeconds)} to go, " +
                    "arriving around ${NavFormat.arrivalTime(progress.remainingDurationSeconds)}."
            Ask.CANCEL -> "There's no route to cancel."
        }
    }

    private fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
