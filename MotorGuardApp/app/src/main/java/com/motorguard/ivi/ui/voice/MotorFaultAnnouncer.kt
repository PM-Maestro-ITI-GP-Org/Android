package com.motorguard.ivi.ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Deciding whether a motor fault is worth interrupting the driver for, and saying it once.
 *
 * The rules are [com.motorguard.ivi.ui.diagnostics.FaultTone]'s, because a beep and a sentence
 * are answering the same question and must not disagree about when something has *happened*:
 * announce the onset, never the continuation, and forget a fault that clears so its return is a
 * new event. What is added on top is escalation — CAUTION becoming CRITICAL is a new thing to
 * say, in the same way [com.motorguard.ivi.ui.diagnostics.AlertTracking] re-surfaces a row the
 * driver already dismissed once it gets worse. De-escalation is silent: nobody needs telling
 * that the car is less broken than it was a moment ago.
 *
 * ### The dwell is the important part
 *
 * `docs/10` §3.4 says this app applies no hysteresis to severity, deliberately, and warns that a
 * classifier oscillating near a boundary makes the dot, the ring and the alert list flicker. A
 * flickering dot is a blemish. A car that starts *talking* every few seconds is a hazard, and one
 * the driver cannot ignore the way they can ignore a light. So speech — and only speech — waits
 * for the reading to hold: 3 seconds, the same floor §3.4 recommends the diagnostics unit apply
 * to its own downgrades.
 *
 * That is not a second opinion on severity. Whatever is eventually said is exactly what arrived;
 * the dwell only decides *when* to believe the unit has settled, and the card is already showing
 * the live value throughout.
 *
 * ### Only from a live reading
 *
 * Nothing is announced from [SignalState.Stale] or [SignalState.Offline]: the fault is old news
 * or unverifiable, and the card says so on its own. A dropped link does not clear what has
 * already been said either, so a reconnect carrying the same fault stays quiet rather than
 * greeting the driver with a fault they were told about ten minutes ago.
 *
 * Pure, and clock-injected, so every one of those rules is a unit test rather than a drive.
 */
internal class MotorAnnouncementPolicy(private val dwellMs: Long = DWELL_MS) {

    private data class Said(val type: MotorFaultType, val severity: Severity)

    private var said: Said? = null
    private var pending: Said? = null
    private var pendingSince = 0L

    /**
     * @return the telemetry to announce, or null to stay quiet. Returns the value rather than a
     *   sentence so the wording stays in one place — [MotorVoice] — and the spoken alert cannot
     *   drift from the answer given when the driver asks about the same fault a moment later.
     */
    fun onState(state: SignalState<MotorTelemetry>, nowMs: Long): MotorTelemetry? {
        val data = (state as? SignalState.Live)?.data
        if (data == null) {
            // A dwell in progress does not survive the signal it was waiting on. `said` does:
            // see the class KDoc on reconnects.
            pending = null
            return null
        }

        if (data.faultType == MotorFaultType.NORMAL && data.faultSeverity == Severity.OK) {
            said = null
            pending = null
            return null
        }

        val now = Said(data.faultType, data.faultSeverity)
        val last = said
        val worthSaying = last == null || now.type != last.type || now.severity > last.severity
        if (!worthSaying) {
            pending = null
            return null
        }

        if (pending != now) {
            pending = now
            pendingSince = nowMs
            return null
        }
        if (nowMs - pendingSince < dwellMs) return null

        said = now
        pending = null
        return data
    }

    /** Forget what has been said, so the next fault announces again. For tests and a data reset. */
    fun reset() {
        said = null
        pending = null
    }

    companion object {
        /** `docs/10` §3.4's recommended dwell floor, applied here to speech only. */
        const val DWELL_MS = 3_000L
    }
}

/**
 * Says it out loud.
 *
 * The platform half of [MotorAnnouncementPolicy] — audio focus, a TTS engine, and the rule that
 * the car does not talk over the driver. Driven from `MainActivity` alongside
 * [com.motorguard.ivi.ui.diagnostics.FaultTone], for the reason recorded there: a fault arriving
 * while the driver is on Media or Nav has to be heard then, not when they next happen to open
 * the diagnostics tab.
 *
 * Holds the application context and a [TextToSpeech], never an Activity, so it can outlive the
 * one that started it without leaking it.
 */
object MotorFaultAnnouncer {

    private const val TAG = "MotorGuardVoice"
    private const val UTTERANCE_ID = "motorguard-motor-alert"

    private val policy = MotorAnnouncementPolicy()

    private var tts: TextToSpeech? = null
    private var appContext: Context? = null
    private var focusRequest: AudioFocusRequest? = null

    /** True for exactly the span of this object's own utterance being spoken -- the proactive
     *  "a fault just showed up" sentence, and nothing else. A driver-initiated question that
     *  happens to be about the motor is answered through [VoiceOverlaySession]'s own TTS, which
     *  never touches this flag, so [com.motorguard.ivi.ui.components.NavRail]'s docked mascot can
     *  key its "big while VEGA is actively volunteering the fault" pop off this alone rather than
     *  off speech in general. */
    val isSpeaking = MutableStateFlow(false)

    /** The cluster code last handed to the C++ core, so the same one is not pushed every second. */
    private var pushedCode: String? = null

    /**
     * Keep the reasoning core's fault list in step with the motor signal.
     *
     * The core answers "any faults?" out of a vector that only [VoiceEngine.pushFault] fills, and
     * for the life of this project nothing called it — so the core replied "I'm not seeing any
     * faults, everything looks fine" with total confidence while the diagnostics screen showed a
     * fault. [MotorVoice] now takes that question before the core can get it wrong, and this
     * closes the hole underneath: whatever else reaches the core, its idea of what is broken is
     * the motor's.
     *
     * Only on a [SignalState.Live] reading, and only on change. A stale or absent signal leaves
     * the last pushed state alone rather than clearing it, because "I cannot see the motor" is
     * not the same claim as "the motor is fine" — which is the whole mistake being fixed.
     *
     * Takes effect once libmotorguardvoice.so is rebuilt; the prebuilt has neither the E codes
     * nor this call's counterpart in its catalogue.
     */
    private fun syncCore(state: SignalState<MotorTelemetry>) {
        val data = (state as? SignalState.Live)?.data ?: return
        val code = MotorVoice.clusterCode(data.faultType)
        if (code == pushedCode) return
        pushedCode = code
        if (code == null) {
            VoiceEngine.clearFaults()
            Log.i(TAG, "core fault list cleared")
        } else {
            VoiceEngine.pushFault(code)
            Log.i(TAG, "core fault list now holds $code")
        }
    }

    /**
     * Feed one reading. Speaks only if this is genuinely new — see [MotorAnnouncementPolicy].
     */
    fun onState(context: Context, state: SignalState<MotorTelemetry>, nowMs: Long = System.currentTimeMillis()) {
        syncCore(state)
        val data = policy.onState(state, nowMs) ?: return

        // Not over an open session. The driver is mid-conversation with the assistant, and the
        // fault is on screen and one question away — interrupting their own question to
        // volunteer it is the one time this is worse than saying nothing.
        if (VoiceOverlaySession.isVisible) {
            Log.i(TAG, "motor fault announcement suppressed: a session is open")
            return
        }

        val text = MotorVoice.announcement(data)
        Log.i(TAG, "announcing motor fault: $text")
        speak(context.applicationContext, text)
    }

    /** Release the engine. The process normally keeps it for its lifetime; this is for teardown. */
    fun shutdown() {
        pushedCode = null
        isSpeaking.value = false
        abandonFocus()
        runCatching { tts?.shutdown() }
        tts = null
        appContext = null
        policy.reset()
    }

    private fun speak(context: Context, text: String) {
        appContext = context
        val engine = tts
        if (engine != null) {
            requestFocus(context)
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
            return
        }
        // First announcement of the process pays for starting the engine. Initialisation is
        // asynchronous, so the phrase is spoken from the callback rather than dropped.
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS unavailable; motor fault will not be announced aloud")
                tts = null
                return@TextToSpeech
            }
            tts?.apply {
                language = Locale.US
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking.value = true
                    }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking.value = false
                        abandonFocus()
                    }
                    override fun onError(utteranceId: String?) {
                        isSpeaking.value = false
                        abandonFocus()
                    }
                })
                requestFocus(context)
                speak(text, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
            }
        }
    }

    /**
     * Transient focus that ducks rather than stops: this is one sentence over whatever is
     * playing, and pausing the music for it would be a bigger interruption than the sentence.
     */
    private fun requestFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (focusRequest != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        runCatching { am.requestAudioFocus(request) }
            .onSuccess { focusRequest = request }
            .onFailure { Log.w(TAG, "could not take audio focus for the fault announcement", it) }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching { am?.abandonAudioFocusRequest(request) }
        focusRequest = null
    }
}
