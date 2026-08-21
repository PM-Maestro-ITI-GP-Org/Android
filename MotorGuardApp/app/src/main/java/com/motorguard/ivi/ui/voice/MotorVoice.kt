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
    fun handle(utterance: String): String? =
        if (claims(utterance)) answerNow(utterance) else null

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
