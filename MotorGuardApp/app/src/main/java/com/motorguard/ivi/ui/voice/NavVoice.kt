package com.motorguard.ivi.ui.voice

import android.content.Context
import android.util.Log
import com.motorguard.ivi.data.nav.NavFormat
import com.motorguard.ivi.data.nav.PlaceCategory
import com.motorguard.ivi.ui.nav.NavPhase
import com.motorguard.ivi.ui.nav.NavSession
import com.motorguard.ivi.ui.nav.SpokenNavResult
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
     * "Where's the nearest ..." — a query for the geocoder and, where the data supports one, a
     * category to filter on.
     */
    internal data class Nearest(val query: String, val category: PlaceCategory?, val noun: String)

    /**
     * Which kind of nearby thing was asked for, or null.
     *
     * Checked ahead of [destinationOf], because "take me to the nearest petrol station" is both
     * a destination phrase and a nearest phrase, and only one of them sorts by distance.
     */
    internal fun nearestOf(utterance: String): Nearest? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null
        if (!NEARBY.any { text.contains(it) }) return null

        // Fuel and charging are separated deliberately. This vehicle is electric, so "charger"
        // must not be answered with a petrol station, and a driver saying "petrol" on a hire car
        // must not be sent to a charge point.
        if (CHARGER.any { text.contains(it) }) {
            return Nearest("charging station", PlaceCategory.CHARGER, "charge point")
        }
        if (FUEL.any { text.contains(it) }) {
            return Nearest("petrol station", PlaceCategory.FUEL, "petrol station")
        }
        if (GARAGE.any { text.contains(it) }) {
            // No category filter: OSM files car repair under shop=car_repair, which Photon maps
            // into SHOP along with every other shop. Filtering on that would be worse than not
            // filtering, so the name is spoken back and the driver judges it.
            return Nearest("car repair", null, "car centre")
        }
        return null
    }

    /** Something has to mark it as a proximity question; "a petrol station" alone does not. */
    private val NEARBY = listOf("nearest", "closest", "near me", "nearby", "around here", "where is a", "wheres a", "where can i")

    private val CHARGER = listOf("charger", "charging", "charge point", "ev point")
    private val FUEL = listOf("petrol", "gas station", "gas ", "fuel", "filling station", "benzine")
    private val GARAGE = listOf(
        "car centre", "car center", "service centre", "service center", "garage", "mechanic",
        "car repair", "repair shop", "workshop", "service station",
    )

    /**
     * Find the closest one and drive there — see [NavSession.navigateToNearest] for why this is
     * not just [navigateTo] with a noun.
     *
     * [SpokenNavResult.NoFix] is answered rather than smoothed over. "Nearest" is a claim about
     * where the car is, and without a fix the only accurate answer is that there isn't one.
     */
    internal suspend fun navigateToNearest(context: Context, ask: Nearest): String {
        NavSession.ensureStarted(context.applicationContext)
        return when (val result = NavSession.navigateToNearest(ask.query, ask.category)) {
            is SpokenNavResult.Started ->
                "Nearest ${ask.noun} is ${result.destination.name}, " +
                    "${NavFormat.distance(result.route.distanceMeters)} away. " +
                    "About ${NavFormat.duration(result.route.durationSeconds)} — heading there now."
            is SpokenNavResult.NoResults -> "I couldn't find a ${ask.noun} near here."
            is SpokenNavResult.NoRoute ->
                "The closest ${ask.noun} I found is ${result.destination.name}, but I can't build a route to it."
            is SpokenNavResult.Failed -> result.message
            SpokenNavResult.NoFix ->
                "I don't have a position fix yet, so I can't tell what's nearest."
            SpokenNavResult.NotReady -> "Navigation isn't ready yet."
        }
    }

    /**
     * The place in "take me to <place>", or null.
     *
     * **"Home" and "work" are refused, not resolved.** They are the two most natural things to
     * say and the two this app cannot answer: nothing stores either address, so searching for the
     * literal word "home" would find a pub called Home and drive there with the same confidence
     * it would show for the right answer. Those keep routing to the Nav tab, where the driver
     * types what they mean. Giving them a real answer is a stored-address feature, not a
     * phrasing one.
     */
    internal fun destinationOf(utterance: String): String? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null
        if (NO_STORED_ADDRESS.any { text.contains(it) }) return null
        val lead = LEAD_INS.firstOrNull { text.startsWith(it) } ?: return null
        var rest = text.removePrefix(lead).trim()
        // "Take me to" on its own reaches the shorter "take me " lead-in and leaves "to" behind;
        // "navigate to the" leaves "the". Both are a search for a preposition, which finds
        // something — that is the danger — so strip them and refuse what is left.
        while (true) {
            val filler = FILLER.firstOrNull { rest == it || rest.startsWith("$it ") } ?: break
            rest = rest.removePrefix(filler).trim()
        }
        return rest.takeIf { it.length >= 2 }
    }

    /**
     * Anchored to the start, so "how far is it to the airport" stays a distance question, and
     * longest first so "take me to " is tested before the "take me " that also matches it.
     *
     * "Find me ..." is deliberately absent: the C++ core answers "find me a garage" from its
     * service-station list, and taking it here would replace a curated answer with a map search.
     */
    private val LEAD_INS = listOf(
        "take me to ", "drive me to ", "navigate to ", "directions to ", "route to ",
        "let s go to ", "lets go to ", "go to ", "take me ",
    ).sortedByDescending { it.length }

    private val FILLER = listOf("to", "the", "a", "an")

    private val NO_STORED_ADDRESS = listOf("home", "work", "the office")

    /**
     * Search, route and go — see [NavSession.navigateTo] for why the first result is taken.
     *
     * The resolved name is always spoken back. That is the whole safeguard: the overlay answers
     * one utterance and cannot ask "did you mean?", so the driver's check is hearing where they
     * are being sent, with "cancel the route" one sentence away.
     */
    suspend fun navigateTo(context: Context, query: String): String {
        NavSession.ensureStarted(context.applicationContext)
        return when (val result = NavSession.navigateTo(query)) {
            is SpokenNavResult.Started ->
                "Heading to ${result.destination.name}. " +
                    "${NavFormat.distance(result.route.distanceMeters)}, " +
                    "about ${NavFormat.duration(result.route.durationSeconds)}."
            is SpokenNavResult.NoResults -> "I couldn't find anywhere called ${result.query}."
            is SpokenNavResult.NoRoute -> "I found ${result.destination.name}, but I can't build a route there."
            is SpokenNavResult.Failed -> result.message
            SpokenNavResult.NotReady -> "Navigation isn't ready yet."
            // Unreachable: navigateTo falls back to NavConfig.defaultOrigin rather than
            // demanding a fix, because a named place is where it is regardless of the car.
            SpokenNavResult.NoFix -> "I don't have a position fix yet."
        }
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
