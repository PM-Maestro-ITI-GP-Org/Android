package com.motorguard.ivi.ui.voice

import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.RemainingLife
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import com.motorguard.ivi.ui.diagnostics.VehicleData
import com.motorguard.ivi.ui.diagnostics.component.TelemetryFormat

/**
 * What the diagnostics unit says about the motor, said out loud.
 *
 * Handled here rather than in the C++ reasoning core for the same reason [com.motorguard.ivi.ui.dialer.PhoneVoice]
 * is: the core reasons about a compiled-in catalogue of DTCs, and this fault is not one. It is a
 * classification produced on another board and pushed over SOME/IP at 1 Hz, and it arrives as
 * [MotorTelemetry] on [VehicleData]'s motor flow — the same value the diagnostics card renders.
 * Reading that flow is what makes the spoken answer and the screen incapable of disagreeing.
 *
 * On an AOSP image that flow is the real link (`motorservice/vehicledata-someip.patch` rewires
 * [VehicleData] at deploy time); on a Gradle build it is the fake. Nothing here can tell the
 * difference, which is the point — this file never names a transport.
 *
 * **Freshness is answered, not ignored.** The core has no notion of a signal going stale, so a
 * fault pushed into it would still be reported as current fifteen minutes after the unit stopped
 * publishing. [SignalState] is carried all the way into the sentence instead: an absent unit is
 * said to be absent, and a last-known reading is dated.
 *
 * Returns null when the utterance is not about the motor, in which case the caller falls through
 * to the matcher and the core untouched.
 */
object MotorVoice {

    /** Which part of the answer the driver actually asked for. */
    private enum class Ask { SUMMARY, TYPE, SEVERITY, LIFE }

    /**
     * @return the line to speak, or null when this is not a motor question.
     */
    fun handle(utterance: String): String? {
        clusterQueryOf(utterance)?.let { return explainCluster(it, VehicleData.source.motor.value) }
        if (claims(utterance)) return answerNow(utterance)
        if (asksForAnyFault(utterance)) return anyFault(VehicleData.source.motor.value)
        return null
    }

    /**
     * "Any faults?" — the question with no subject in it.
     *
     * It has to be answered here because the alternative answers it wrongly. Left to fall
     * through, it reaches the C++ core's ListFaults intent, which replies "I'm not seeing any
     * faults at the moment, everything looks fine" out of a vector that only
     * [VoiceEngine.pushFault] can fill — and nothing has ever called it. The core is therefore
     * incapable of reporting a fault, and says so with total confidence, while the diagnostics
     * screen two feet away shows the fault it is denying.
     *
     * A wrong "everything looks fine" is the worst answer this assistant can give, so the
     * question is taken before it can be asked of something that cannot know.
     */
    internal fun asksForAnyFault(utterance: String): Boolean {
        val text = normalise(utterance)
        if (text.isEmpty()) return false
        return ANY_FAULT.any { text.contains(it) }
    }

    private val ANY_FAULT = listOf(
        "any faults", "any fault", "any errors", "any error", "any problems", "any problem",
        "any issues", "what faults", "what errors", "anything wrong", "something wrong",
        "is everything ok", "is everything okay", "everything alright", "all good",
    )

    /**
     * The same reading the card shows, plus the code the cluster prints for it — because someone
     * asking whether anything is wrong is often looking at a dashboard while they ask.
     */
    internal fun anyFault(state: SignalState<MotorTelemetry>): String {
        val body = compose("is there a fault in the motor", state, System.currentTimeMillis())
        val code = (state as? SignalState.Live)?.data?.faultType?.let { clusterCode(it) }
        return if (code == null) body else "$body The cluster shows that as $code."
    }

    // --- the cluster's codes -------------------------------------------------

    /**
     * What the driver is reading off the instrument cluster.
     *
     * The cluster is a separate Qt application on its own display (the `qt-cluster` repo), and it
     * prints a code where this app prints a sentence. Both are derived from the same thing — the
     * AI board's fault class, which arrives here as [MotorFaultType] and there as
     * `Vehicle.aiFaultClass` — so the assistant can already answer "what is E-31" without anyone
     * writing a fault catalogue for it. What it could not do was recognise the question.
     */
    internal sealed interface ClusterQuery {
        /** A code the driver read out. */
        data class Explicit(val code: String) : ClusterQuery

        /** "What's that code on the cluster" — the code is on the screen, not in the utterance. */
        data object Current : ClusterQuery
    }

    /**
     * The code the cluster shows for a fault type, mirroring `Main.qml`'s `errorFault`.
     *
     * The families are the entire meaning of the number: 2x is electrical, 3x is mechanical, and
     * E-01 is a fault raised but placed in neither. [MotorFaultType.SENSOR] lands on E-01 for the
     * same reason it does there — its class string matches neither family — which is also the
     * answer this assistant already gives in words.
     *
     * Null for [MotorFaultType.NORMAL]: no fault, no code, and the cluster draws nothing.
     */
    internal fun clusterCode(type: MotorFaultType): String? = when (type) {
        MotorFaultType.NORMAL -> null
        MotorFaultType.ELECTRICAL -> "E-21"
        MotorFaultType.MECHANICAL -> "E-31"
        MotorFaultType.SENSOR -> "E-01"
    }

    internal fun clusterQueryOf(utterance: String): ClusterQuery? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null
        // A destination or a contact is never a fault code, and "e" is a short enough token to be
        // worth protecting from the handlers that run after this one.
        if (NOT_A_CODE.any { text.contains(it) }) return null

        parseCode(text)?.let { return ClusterQuery.Explicit(it) }
        if (CURRENT_CODE.any { text.contains(it) }) return ClusterQuery.Current
        return null
    }

    /**
     * What to say when the driver is looking at a code and this side has nothing.
     *
     * Emphatically not "the cluster shouldn't be showing a code". The cluster is a separate
     * application reading the AI board over its own link; it is the authority on what the cluster
     * is showing, and this app is not entitled to overrule the driver's own dashboard from a
     * signal that may simply not have arrived. The two disagreeing is a fact worth stating and a
     * reason to ask, not a reason to contradict.
     */
    private const val NOTHING_HERE =
        "I'm not seeing a fault on my side — my link to the diagnostics unit may not be up. " +
            "If the cluster is showing a code, read it out and I'll tell you what it means: " +
            "E-21 is electrical, E-31 mechanical, E-01 a fault it couldn't place."

    /**
     * A spoken code, however the recogniser wrote it down.
     *
     * This was a regex wanting two adjacent digits, which is only one of the several things
     * "E-31" comes back as. People read codes out a digit at a time — "E three one" — and the
     * transcription then depends on the recogniser's mood: "e 3 1", "e three one", "e three 1".
     * All of those missed, and the code only worked when the whole number happened to be
     * transcribed as one token. A driver who has to discover the one wording that works has been
     * given a password, not an assistant.
     *
     * So the digits are collected token by token from whatever follows the "e", accepting figures
     * and words interchangeably. A single digit is zero-padded, because "E one" can only be
     * E-01 — and if it were not, the unknown-code reply names the three that exist anyway.
     */
    internal fun parseCode(text: String): String? {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        for (i in tokens.indices) {
            // "e31" or "e-31" — the hyphen is already a space by the time this runs, but some
            // recognisers emit it closed up.
            JOINED.matchEntire(tokens[i])?.let { return format(it.groupValues[1]) }
            if (tokens[i] != "e") continue

            val digits = StringBuilder()
            var j = i + 1
            while (j < tokens.size && digits.length < 2) {
                digits.append(digitsOf(tokens[j]) ?: break)
                j++
            }
            if (digits.isNotEmpty()) return format(digits.toString())
        }
        return null
    }

    private val JOINED = Regex("e(\\d{1,2})")

    /** One spoken token as the digits it stands for, or null if it is not a number at all. */
    private fun digitsOf(token: String): String? = when {
        token.all { it.isDigit() } -> token
        else -> NUMBER_WORDS[token]
    }

    /**
     * "Thirty" is 3 rather than 30 on purpose: it is only ever seen here as the first half of
     * "thirty one", and the digits are being collected one place at a time.
     */
    private val NUMBER_WORDS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "nought" to "0",
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13",
        "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
        "eighteen" to "18", "nineteen" to "19",
        "twenty" to "2", "thirty" to "3", "forty" to "4", "fifty" to "5",
        // Whole numbers, for a recogniser that writes the words out rather than the figures.
        "twentyone" to "21", "thirtyone" to "31",
    )

    private fun format(digits: String): String =
        if (digits.length == 1) "E-0$digits" else "E-" + digits.take(2)

    /**
     * Asking about what is on the dashboard, without naming a code.
     *
     * The first version required the word "code" next to the word "cluster", so "what's this
     * error on my cluster" — which is how the question actually gets asked — matched nothing
     * here, fell through to the embedding matcher, and came back as a general motor status
     * report. Built as a surface crossed with a noun instead: the driver may call it a code, an
     * error, a warning or a light, and the thing showing it may be the cluster, the dash, the
     * display or the screen.
     */
    private val CURRENT_CODE: List<String> = buildList {
        val surfaces = listOf("cluster", "dash", "dashboard", "display", "screen")
        val nouns = listOf("code", "error", "warning", "fault", "light")
        for (n in nouns) for (sf in surfaces) {
            add("$n on the $sf")
            add("$n on my $sf")
            add("$n on $sf")
        }
        // Said while pointing at it, with the surface left implicit.
        addAll(
            listOf(
                "what is that code", "whats that code", "what s that code",
                "what is this code", "whats this code", "what s this code",
                "error code", "fault code", "code is showing", "code showing",
                "what is that error", "whats that error", "what s that error",
                "what is this error", "whats this error", "what s this error",
            ),
        )
    }

    private val NOT_A_CODE = listOf("take me", "navigate", "drive me", "call ", "dial ")

    /**
     * Explain a code, and say whether it still matches.
     *
     * The cross-check is the part worth having. The cluster and this app read the same
     * classification over two different links, so a code on the driver's dash and a fault on this
     * side *should* agree — and the one moment it is worth knowing they do not is exactly when
     * someone is asking. Where this side's signal is stale or offline it says so rather than
     * asserting a match it cannot stand behind.
     */
    internal fun explainCluster(query: ClusterQuery, state: SignalState<MotorTelemetry>): String {
        val live = (state as? SignalState.Live)?.data
        val code = when (query) {
            is ClusterQuery.Explicit -> query.code
            ClusterQuery.Current -> {
                live ?: return "I can't reach the motor diagnostics unit, so I can't tell you " +
                    "what the cluster is showing."
                clusterCode(live.faultType) ?: return NOTHING_HERE
            }
        }

        val meaning = when (code) {
            "E-21" -> "E-21 is an electrical fault on the motor — the twenty-series codes are the " +
                "electrical family."
            "E-31" -> "E-31 is a mechanical fault on the motor — the thirty-series codes are the " +
                "mechanical family: a bearing, the rotor, a shaft, imbalance or vibration."
            "E-01" -> "E-01 means the cluster has a fault raised but couldn't place it in either " +
                "family. It's a real fault, just not a named one."
            // Not invented. Three codes exist; anything else is misheard or newer than this build,
            // and guessing at the meaning of a fault code is the one answer worth refusing.
            else -> return "$code isn't a code I know. The cluster shows E-21 for electrical, " +
                "E-31 for mechanical, and E-01 for a fault it can't place."
        }

        if (query is ClusterQuery.Current) return meaning
        val liveCode = live?.let { clusterCode(it.faultType) }
        val check = when {
            live == null -> " I can't check it against the motor right now."
            // Not "so it should have cleared". The driver is looking at the code; this side not
            // seeing the fault is at least as likely to mean the link is down as it is to mean
            // the fault has gone, and only one of those two readings is checkable from here.
            liveCode == null -> " I'm not seeing that fault on my side though — either it's " +
                "cleared, or my link to the diagnostics unit isn't up."
            liveCode == code -> " That matches what the motor is reporting now."
            else -> " The motor is reporting $liveCode now, so the cluster should have moved on."
        }
        return meaning + check
    }

    /**
     * The answer regardless of whether [claims] would have taken the utterance — for the
     * embedding matcher, which has already decided this is a motor question by meaning rather
     * than by wording.
     */
    fun answerNow(utterance: String): String =
        compose(utterance, VehicleData.source.motor.value, System.currentTimeMillis())

    /**
     * The sentence to say unprompted when a fault appears.
     *
     * Deliberately the same sentence [answerNow] gives when the driver asks — no alarm word, no
     * second phrasing. It already opens by naming the diagnostics unit, so it identifies itself
     * as the car speaking without being dressed up, and a driver who then asks "what was that"
     * hears exactly what they just heard rather than a variant that leaves them wondering
     * whether it is the same fault. [MotorFaultAnnouncer] decides *whether* to say it.
     */
    internal fun announcement(data: MotorTelemetry): String = summary(data)

    // --- what counts as a motor question -----------------------------------

    /**
     * Keyword matching, deliberately, and deliberately narrow.
     *
     * [com.motorguard.ivi.ui.voice.nlu.IntentMatcher] is the paraphrase net and it is better at
     * this — but it needs an embedding model that is not on every image, and it declines anything
     * below 0.60. This is the floor underneath it: the exact words someone says when they are
     * looking at a red dot on the motor.
     *
     * The exclusion for warning lights is not incidental. "What's the check engine light" contains
     * "engine", and the core's fault catalogue answers it properly where this could only talk
     * about a BLDC motor the question was not about.
     */
    internal fun claims(utterance: String): Boolean {
        val text = normalise(utterance)
        if (text.isEmpty()) return false
        if (NOT_OURS.any { text.contains(it) }) return false
        if (STANDALONE.any { text.contains(it) }) return true

        val words = text.split(' ').toHashSet()
        return MOTOR_WORDS.any { it in words } &&
            (STATE_WORDS.any { it in words } || STATE_PHRASES.any { text.contains(it) })
    }

    /** The subject has to be named; "how is it doing" is not something to answer about a motor. */
    private val MOTOR_WORDS = setOf("motor", "engine", "drivetrain", "powertrain")

    /**
     * ...and something has to be asked about it.
     *
     * "Doing", "running" and "sounds" are here because they are how the question is actually
     * asked out loud — "how is the motor doing" is the most natural phrasing there is and it
     * matched nothing, since the first version of this list only held words for a fault that had
     * already been named. The embedding matcher covers them too, but only on an image carrying
     * the model, and this floor exists for the images that do not.
     */
    private val STATE_WORDS = setOf(
        "fault", "faults", "faulty", "wrong", "problem", "problems", "issue", "issues",
        "ok", "okay", "fine", "healthy", "health", "status", "condition",
        "electrical", "mechanical", "serious", "bad", "safe", "failing", "broken",
        "life", "rul", "vibration", "vibrating",
        "doing", "running", "runs", "run", "sound", "sounds", "sounding", "noise", "noisy",
    )

    private val STATE_PHRASES = listOf("how long", "how much longer", "left on", "diagnos")

    /**
     * Phrases that name the motor's fault without using the word — the follow-up questions, plus
     * the unit itself. "The diagnostics unit" is unambiguous here: the motor is the only signal
     * on this vehicle that a unit reports at all.
     */
    private val STANDALONE = listOf(
        "electrical or mechanical", "mechanical or electrical",
        "remaining useful life", "remaining life",
        "diagnostics unit", "diagnostic unit",
    )

    /**
     * Left to the core, which knows about dashboard lights and has a catalogue to explain them.
     * Also left alone: anything that sounds like an instruction to the motor rather than a
     * question about it.
     */
    private val NOT_OURS = listOf(
        "light", "start the", "turn the engine", "switch the engine", "turn off the engine",
    )

    // --- composing the answer ----------------------------------------------

    /**
     * Pure, so the sentences are unit-testable without a device: everything that varies is a
     * parameter, and nothing here logs.
     */
    internal fun compose(utterance: String, state: SignalState<MotorTelemetry>, nowMs: Long): String {
        val ask = askOf(normalise(utterance))
        return when (state) {
            is SignalState.Loading ->
                "I haven't had a reading from the motor diagnostics unit yet."
            is SignalState.Offline ->
                "I can't reach the motor diagnostics unit, so I don't have a reading for the motor."
            is SignalState.Live -> body(state.data, ask)
            is SignalState.Stale ->
                "The motor diagnostics unit stopped reporting ${TelemetryFormat.age(nowMs - state.lastTimestampMs)}. " +
                    "Its last reading: " + decapitalise(body(state.lastData, ask))
        }
    }

    private fun askOf(text: String): Ask = when {
        TYPE_ASKS.any { text.contains(it) } -> Ask.TYPE
        LIFE_ASKS.any { text.contains(it) } -> Ask.LIFE
        SEVERITY_ASKS.any { text.contains(it) } -> Ask.SEVERITY
        else -> Ask.SUMMARY
    }

    private val TYPE_ASKS = listOf(
        "electrical or mechanical", "mechanical or electrical",
        "what kind", "what sort", "what type", "which kind", "which type",
    )
    private val LIFE_ASKS = listOf(
        "how long", "how much longer", "remaining life", "remaining useful life",
        "life left", "left on it", "rul",
    )
    private val SEVERITY_ASKS = listOf(
        "how bad", "how serious", "is it serious", "is it urgent", "should i",
        "safe to drive", "can i drive", "can i keep driving", "how severe",
    )

    private fun body(data: MotorTelemetry, ask: Ask): String = when (ask) {
        Ask.TYPE -> typeSentence(data)
        Ask.LIFE -> lifeSentence(data.remainingLife, standalone = true)
        Ask.SEVERITY -> severitySentence(data)
        Ask.SUMMARY -> summary(data)
    }

    private fun summary(data: MotorTelemetry): String {
        val head = when (data.faultType) {
            MotorFaultType.NORMAL -> when (data.faultSeverity) {
                // NORMAL means the classifier ran and found nothing (see MotorTelemetry). A
                // severity above OK alongside it is the unit contradicting itself, and saying so
                // is more use than picking one half to repeat.
                Severity.OK -> "The motor diagnostics unit reports no fault."
                else -> "The motor diagnostics unit reports no fault type, but it has raised " +
                    "${severityWord(data.faultSeverity)}, so it's worth a look on the diagnostics screen."
            }
            MotorFaultType.ELECTRICAL ->
                "The motor diagnostics unit has found an electrical fault, ${severityClause(data.faultSeverity)}"
            MotorFaultType.MECHANICAL ->
                "The motor diagnostics unit has found a mechanical fault, ${severityClause(data.faultSeverity)}"
            // Not named. The unit classifies electrical and mechanical, and those are the two
            // words this assistant says; anything else it sends is reported as a fault with the
            // severity it arrived with and no type. Reporting it unnamed rather than not at all
            // is the part that matters — a fault the unit raised must never become silence here,
            // or the voice would say the motor is fine while the card shows a coloured dot.
            MotorFaultType.SENSOR ->
                "The motor diagnostics unit has flagged a fault, ${severityClause(data.faultSeverity)}"
        }
        val life = lifeSentence(data.remainingLife, standalone = false)
        return if (life.isEmpty()) head else "$head $life"
    }

    /**
     * Electrical or mechanical, and nothing finer.
     *
     * Those are the two classes the model on the diagnostics unit produces and the two the driver
     * is being asked to act on. [MotorFaultType] carries a third, and the wire contract a fourth
     * (`docs/10` §3.2), but subdividing further in speech buys a word nobody uses and costs the
     * clarity of a two-way answer.
     */
    private fun typeSentence(data: MotorTelemetry): String = when (data.faultType) {
        MotorFaultType.NORMAL ->
            "There's no fault on the motor at the moment, so there's nothing to call electrical or mechanical."
        MotorFaultType.ELECTRICAL -> "It's an electrical fault, ${severityClause(data.faultSeverity)}"
        MotorFaultType.MECHANICAL -> "It's a mechanical fault, ${severityClause(data.faultSeverity)}"
        MotorFaultType.SENSOR ->
            "Neither — the unit is reporting a fault but hasn't called it electrical or mechanical, " +
                severityClause(data.faultSeverity)
    }

    private fun severitySentence(data: MotorTelemetry): String {
        val named = when (data.faultType) {
            MotorFaultType.NORMAL -> "There's no motor fault reported"
            MotorFaultType.ELECTRICAL -> "The electrical fault on the motor is"
            MotorFaultType.MECHANICAL -> "The mechanical fault on the motor is"
            MotorFaultType.SENSOR -> "The fault on the motor is"
        }
        if (data.faultType == MotorFaultType.NORMAL) {
            return if (data.faultSeverity == Severity.OK) "$named, and nothing is flagged."
            else "$named, but the unit has still raised ${severityWord(data.faultSeverity)}."
        }
        // OK alongside a real fault type is the unit saying "classified, not yet worrying" — a
        // sentence, not a severity word, because "the fault is no alert" is not English.
        if (data.faultSeverity == Severity.OK) return "$named classified, but it isn't flagged as a problem yet."
        return "$named ${severityWord(data.faultSeverity)}. ${advice(data.faultSeverity)}"
    }

    /** Trailing clause of a fault sentence; always ends the sentence. */
    private fun severityClause(severity: Severity): String = when (severity) {
        // The unit's own severity, passed through: docs/09 §2.3 is explicit that this app applies
        // no second opinion, so the words here only name what arrived.
        Severity.OK -> "though it isn't flagging it as a problem yet."
        Severity.CAUTION -> "flagged as a caution. ${advice(Severity.CAUTION)}"
        Severity.CRITICAL -> "flagged as critical. ${advice(Severity.CRITICAL)}"
    }

    private fun severityWord(severity: Severity): String = when (severity) {
        Severity.OK -> "no alert"
        Severity.CAUTION -> "a caution"
        Severity.CRITICAL -> "a critical alert"
    }

    private fun advice(severity: Severity): String = when (severity) {
        Severity.OK -> ""
        Severity.CAUTION -> "Worth booking a service, but it isn't urgent."
        Severity.CRITICAL -> "I'd get the motor looked at before driving it much further."
    }

    /**
     * Remaining useful life, or nothing at all.
     *
     * Null is a real answer from the unit — no estimate — and it is only spoken when the driver
     * asked for the figure specifically. Volunteering "it hasn't given an estimate" at the end of
     * every fault summary would be noise on the sentence that matters.
     */
    private fun lifeSentence(life: RemainingLife?, standalone: Boolean): String {
        if (life == null) {
            return if (standalone) "The unit hasn't given an estimate of remaining life for the motor." else ""
        }
        val hours = TelemetryFormat.hours(life.hours)
        val percent = life.percent?.let { ", about ${TelemetryFormat.percent(it)} of its life" } ?: ""
        return "It estimates around $hours of useful life left$percent."
    }

    // --- text --------------------------------------------------------------

    /** Case and punctuation are not something a driver should have to get right. */
    private fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** So a dated last reading reads as one sentence rather than two glued together. */
    private fun decapitalise(sentence: String): String =
        if (sentence.length > 1 && sentence[1].isLowerCase()) sentence.replaceFirstChar { it.lowercase() }
        else sentence
}
