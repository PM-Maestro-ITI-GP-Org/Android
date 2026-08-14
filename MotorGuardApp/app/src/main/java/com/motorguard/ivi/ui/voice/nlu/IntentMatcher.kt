package com.motorguard.ivi.ui.voice.nlu

import android.content.Context
import android.util.Log
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
     * How close is close enough.
     *
     * Cosine similarity on this model runs high — unrelated sentences still score around 0.2,
     * and paraphrases sit near 0.6. Set too low, everything becomes an intent and the fallback
     * never runs; set too high, only near-quotes match and the whole exercise is pointless.
     */
    private const val THRESHOLD = 0.48f

    /** Taught commands win outright: a phrase the owner wrote down beats one that ships. */
    private const val TAUGHT_BONUS = 0.05f

    private var embedder: TextEmbedder? = null
    private var anchors: List<Anchor> = emptyList()

    @Volatile
    private var ready = false

    /** One phrasing of one intent, with its vector. */
    private class Anchor(val vector: FloatArray, val result: Match)

    /** What the matcher concluded. [reply] is set only for a taught command. */
    data class Match(val route: VoiceRoute?, val reply: String?, val confidence: Float = 0f)

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

        val built = ArrayList<Anchor>(64)
        BUILT_IN.forEach { (route, phrases) ->
            phrases.forEach { phrase ->
                loaded.embed(phrase)?.let { built += Anchor(it, Match(route, null)) }
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

        var best: Match? = null
        var bestScore = 0f

        for (anchor in anchors) {
            val score = dot(query, anchor.vector)
            if (score > bestScore) { bestScore = score; best = anchor.result }
        }

        // Taught phrases are embedded on demand rather than at startup: the list changes while
        // the app runs, and re-embedding a handful of short strings costs less than keeping a
        // cache correct across every edit.
        for (command in VoiceCommands.commands.value) {
            val vector = model.embed(command.trigger) ?: continue
            val score = dot(query, vector) + TAUGHT_BONUS
            if (score > bestScore) {
                bestScore = score
                best = Match(route = null, reply = command.reply)
            }
        }

        if (best == null || bestScore < THRESHOLD) {
            Log.i(TAG, "no intent above threshold (best %.2f)".format(bestScore))
            return null
        }
        Log.i(TAG, "intent matched %.2f -> %s".format(bestScore, best.route?.name ?: "taught"))
        return best.copy(confidence = bestScore)
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
        VoiceRoute.MEDIA to listOf(
            "play some music",
            "put a song on",
            "I want to listen to something",
            "turn the radio on",
            "next track",
        ),
        VoiceRoute.NAV to listOf(
            "take me home",
            "navigate somewhere",
            "give me directions",
            "how do I get there",
            "where am I",
        ),
        VoiceRoute.PHONE to listOf(
            "call someone",
            "ring my contact",
            "make a phone call",
            "dial a number",
        ),
        VoiceRoute.DIAGNOSTICS to listOf(
            "how is the car doing",
            "is anything wrong with the vehicle",
            "check the battery",
            "what is my tyre pressure",
            "show me the warning lights",
        ),
        VoiceRoute.SETTINGS to listOf(
            "open the settings",
            "change the display",
            "connect to wifi",
            "pair my phone",
        ),
    )
}
