package com.motorguard.ivi.ui.voice.nlu

import android.content.Context
import android.util.Log
import com.motorguard.ivi.data.VoiceCommand
import com.motorguard.ivi.data.VoiceCommands
import com.motorguard.ivi.ui.voice.ModelPaths
import com.motorguard.ivi.ui.voice.VoiceRoute

/**
 * What the driver meant, rather than what they happened to say.
 *
 * Keyword matching only fires on the words someone thought of in advance. "It's freezing in
 * here" shares nothing with "climate", and "put some music on" shares nothing with "play" if
 * the author only wrote "play music" — so the assistant answered with the generic apology and
 * looked stupid for a reason that had nothing to do with understanding.
 *
 * Each intent carries a handful of ways people actually phrase it. Those are embedded once,
 * the utterance is embedded when it arrives, and the nearest one wins if it is near enough.
 * Being a vector comparison, an unseen phrasing lands next to the examples it resembles
 * without anyone having listed it.
 *
 * Phrases taught in Settings are matched the same way, so "how are the tyres doing" reaches a
 * command taught as "tyre pressure" — the exact-substring rule in [VoiceCommands] would not.
 *
 * Below the threshold this returns null and says so. That is the point of the confidence: the
 * generative fallback exists for everything this cannot place, and a matcher that always
 * answers would leave nothing for it to do.
 */
object IntentMatcher {

    private const val TAG = "MotorGuardVoice"

    /**
     * How close is close enough, measured rather than guessed.
     *
     * Scored against this model over a test set of phrasings that should route and questions
     * that should not:
     *
     *   should route, lowest      0.407  "find me a garage"
     *   should route, typical     0.60 - 1.00
     *   should NOT route, highest 0.563  "explain the check engine light"
     *   should NOT route, next    0.474  "how old are you"
     *
     * The ranges overlap, so no threshold both catches everything and refuses everything —
     * an earlier 0.48 would have routed "how old are you" to Navigation. 0.60 is the point
     * where nothing is misrouted at all. What it costs is a few real requests falling through
     * to the core instead, which is the right way round in a car: an unhelpful answer is a
     * moment's annoyance, a tab changing under the driver's hands is not.
     *
     * The highest near-miss is a warning-light question, and it *should* miss: the core has the
     * fault catalogue and can answer it properly, where this could only open the tab.
     */
    private const val THRESHOLD = 0.60f

    /**
     * The bar for a taught phrase, set below [THRESHOLD] on purpose.
     *
     * A built-in anchor is a guess about how people might ask for something, so it should have
     * to earn a match. A taught phrase is the owner stating what they will say and what should
     * happen — being stricter with their own words than with ours would be backwards.
     *
     * It can also be far lower, because the measured separation is enormous. Against a taught
     * "tyre pressure":
     *
     *   "what is my tyre pressure"  0.915      "play some music"  0.010
     *   "are my tyres okay"         0.559      "call my wife"     0.037
     *   "how are the tyres doing"   0.536      "take me home"    -0.007
     *   "check my tires"            0.421      "what time is it"  0.024
     *
     * Everything related sits above 0.42 and everything unrelated below 0.04, so 0.35 catches
     * the loosest paraphrase with an order of magnitude to spare.
     */
    private const val TAUGHT_THRESHOLD = 0.35f

    private var embedder: TextEmbedder? = null
    private var anchors: List<Anchor> = emptyList()

    /** Taught phrases with their vectors, and the list they were built from. */
    private var taughtCache: List<Pair<VoiceCommand, FloatArray>> = emptyList()
    private var taughtSignature: String? = null

    @Volatile
    private var ready = false

    /** One phrasing of one intent, with its vector. */
    private class Anchor(val vector: FloatArray, val result: Match)

    /**
     * What the matcher concluded.
     *
     * Exactly one of the three is set. [reply] is a taught command's answer, [route] opens a tab,
     * and [ask] is a question the assistant answers itself from live vehicle data — a tab is not
     * an answer to "is the motor fault electrical or mechanical", and the driver asking it is
     * usually not in a position to read one.
     */
    data class Match(
        val route: VoiceRoute?,
        val reply: String?,
        val ask: VoiceAsk? = null,
        val confidence: Float = 0f,
    )

    /**
     * Load the model and embed the anchors. Idempotent, and safe to call when the model files
     * are not on the image — it simply stays unavailable and [match] returns null.
     */
    @Synchronized
    fun ensureReady(context: Context) {
        if (ready) return
        ready = true

        val filesDir = context.filesDir
        val model = ModelPaths.file(filesDir, TextEmbedder.MODEL_FILE)
        val vocab = ModelPaths.file(filesDir, TextEmbedder.VOCAB_FILE)
        if (model == null || vocab == null) {
            Log.i(TAG, "no embedding model on this image; intent matching disabled")
            return
        }

        val loaded = TextEmbedder.load(model, vocab) ?: return
        embedder = loaded

        val built = ArrayList<Anchor>(80)
        BUILT_IN.forEach { (route, phrases) ->
            phrases.forEach { phrase ->
                loaded.embed(phrase)?.let { built += Anchor(it, Match(route, null)) }
            }
        }
        ANSWERED.forEach { (ask, phrases) ->
            phrases.forEach { phrase ->
                loaded.embed(phrase)?.let { built += Anchor(it, Match(null, null, ask)) }
            }
        }
        anchors = built
        Log.i(TAG, "intent matcher ready (${built.size} anchors)")
    }

    /**
     * @return the closest intent, or null when nothing is close enough to act on.
     */
    fun match(utterance: String): Match? {
        val model = embedder ?: return null
        val query = model.embed(utterance) ?: return null

        // Taught phrases are considered first and win outright, rather than competing on
        // score with the built-in anchors.
        //
        // Competing does not work. Teach "car health" and it goes up against the shipped
        // Diagnostics anchor "is the car healthy" — nearly the same sentence, so the two score
        // within a hair of each other and which one wins is a coin toss decided by wording
        // nobody can see. Asking about the car would sometimes open the Diagnostics tab instead
        // of saying the answer that was written for exactly that question.
        //
        // Teaching a phrase is an explicit instruction about what should happen when it is
        // said, so it takes precedence over anything inferred, at a slightly easier bar.
        var bestTaught: VoiceCommand? = null
        var bestTaughtScore = 0f
        for ((command, vector) in taughtVectors(model)) {
            val score = dot(query, vector)
            if (score > bestTaughtScore) { bestTaughtScore = score; bestTaught = command }
        }
        if (bestTaught != null && bestTaughtScore >= TAUGHT_THRESHOLD) {
            Log.i(TAG, "taught phrase matched %.2f -> \"%s\"".format(bestTaughtScore, bestTaught.trigger))
            return Match(route = null, reply = bestTaught.reply, confidence = bestTaughtScore)
        }

        var best: Match? = null
        var bestScore = 0f
        for (anchor in anchors) {
            val score = dot(query, anchor.vector)
            if (score > bestScore) { bestScore = score; best = anchor.result }
        }

        if (best == null || bestScore < THRESHOLD) {
            Log.i(
                TAG,
                "no intent above threshold (built-in %.2f, taught %.2f)"
                    .format(bestScore, bestTaughtScore),
            )
            return null
        }
        Log.i(
            TAG,
            "intent matched %.2f -> %s"
                .format(bestScore, best.route?.name ?: best.ask?.name ?: "taught"),
        )
        return best.copy(confidence = bestScore)
    }

    /**
     * Taught phrases with their vectors, embedded once and reused.
     *
     * The first version embedded every taught phrase on every utterance, which is one model
     * run per command per question — fine for the two commands it was tested with, and
     * steadily worse for an owner who actually uses the feature. The cache is rebuilt when the
     * list changes, which is the only time it can go stale, and edits are rare next to
     * questions.
     */
    @Synchronized
    private fun taughtVectors(model: TextEmbedder): List<Pair<VoiceCommand, FloatArray>> {
        val current = VoiceCommands.commands.value
        val signature = current.joinToString("|") { it.id + ":" + it.trigger }
        if (signature != taughtSignature) {
            taughtCache = current.mapNotNull { command ->
                model.embed(command.trigger)?.let { command to it }
            }
            taughtSignature = signature
            Log.i(TAG, "embedded ${taughtCache.size} taught phrase(s)")
        }
        return taughtCache
    }

    /** Both vectors are unit length, so the dot product is the cosine. */
    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /**
     * Ways people ask for each thing.
     *
     * Deliberately phrased as a driver would speak, not as a menu item reads. The vectors are
     * only as good as the examples: "media" as an anchor matches nothing anyone says out loud,
     * while "put some music on" pulls in every neighbouring phrasing with it.
     */
    private val BUILT_IN: Map<VoiceRoute, List<String>> = mapOf(
        // Browsing only. The transport phrasings that used to live here — skip, pause, stop,
        // turn it up — are commands now, claimed by MediaVoice before this runs, and an anchor
        // advertising a route they no longer take would just be a lie in a list.
        VoiceRoute.MEDIA to listOf(
            "play some music", "put something on", "put a song on",
            "i want to listen to something",
            "play my playlist", "some tunes please",
        ),
        // "how long until we arrive" has an answer now, and "take me to <place>" is acted on;
        // see ANSWERED below and NavVoice.destinationOf. What is left here is what still needs
        // the tab: the two stored-address phrasings this app has no address for, and the
        // open-ended asks with no destination in them to extract.
        VoiceRoute.NAV to listOf(
            "take me home", "drive me home", "navigate somewhere", "give me directions",
            "how do i get there", "where am i", "route to work", "show me the map",
        ),
        VoiceRoute.PHONE to listOf(
            "call someone", "ring mona", "phone my wife", "make a phone call",
            "dial a number", "give her a ring", "call the office", "answer the phone",
            "hang up",
        ),
        // No tyre, battery or charge phrasings. This vehicle has no such sensor: those five
        // signals come from the fake source even on the board (motorservice/README.md, "Only the
        // motor comes off this link"), so an anchor for them invites a spoken question whose only
        // possible answer is an invented number on a tab.
        VoiceRoute.DIAGNOSTICS to listOf(
            "how is the car doing", "is anything wrong with the vehicle",
            "show me the warning lights", "is the car healthy",
            "any faults", "what is that warning light",
        ),
        VoiceRoute.SETTINGS to listOf(
            "open settings", "open the settings", "change the display", "connect to wifi",
            "pair my phone", "turn on bluetooth", "make the screen darker", "change the theme",
        ),
        VoiceRoute.VIDEO to listOf(
            "watch a video", "watch some video", "open videos", "open the videos",
            "play a video", "put a video on", "watch youtube", "open youtube", "play youtube",
        ),
    )

    /**
     * Ways people ask about the motor itself, which the assistant answers out loud instead of
     * routing.
     *
     * Kept apart from [BUILT_IN] and phrased around the motor rather than the car, because these
     * anchors sit next to the DIAGNOSTICS ones and the two must not trade places: "is the car
     * healthy" should still open the tab, where "is the motor okay" has a one-sentence answer that
     * the diagnostics unit already computed. Nearest-anchor-wins is what keeps them apart, so the
     * word that separates them has to be in every phrase here.
     *
     * [MotorVoice.claims] covers the same ground by keyword and runs first, so an image without
     * the embedding model still answers the common wordings. This is the paraphrase net over it.
     */
    private val ANSWERED: Map<VoiceAsk, List<String>> = mapOf(
        VoiceAsk.MOTOR_STATUS to listOf(
            "is there something wrong with the motor", "what is wrong with the motor",
            "does the motor have a fault", "is the motor okay", "how is the motor doing",
            "is the fault electrical or mechanical", "what kind of motor fault is it",
            "how bad is the motor fault", "is the motor fault serious",
            "how long has the motor got left", "what is the remaining life of the motor",
            "what did the diagnostics unit say about the motor",
            "is it safe to keep driving with this motor fault",
        ),
        VoiceAsk.NOW_PLAYING to listOf(
            "what is playing", "what song is this", "who sings this",
            "what am i listening to", "what is this track",
        ),
        VoiceAsk.TRIP_PROGRESS to listOf(
            "how long until we arrive", "when will we get there", "what is our eta",
            "how much further is it", "are we nearly there",
        ),
    )
}

/**
 * A question the assistant answers from live vehicle data rather than by opening a tab.
 *
 * One member today, and the motor is the only signal on this vehicle with a real sensor behind
 * it, so it may well stay that way. It is an enum rather than a boolean because if a second
 * signal ever does arrive, it should be a new case here and a new branch at the call site, not a
 * second flag nobody remembers to check.
 */
enum class VoiceAsk { MOTOR_STATUS, NOW_PLAYING, TRIP_PROGRESS }
