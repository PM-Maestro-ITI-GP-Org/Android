package com.motorguard.ivi.ui.diagnostics

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity

/**
 * A short beep the moment something goes wrong, and not once more until it does again.
 *
 * WHEN IT FIRES
 * -------------
 * On the *onset* of a fault, never for one that is merely still there. A car that beeps every
 * few seconds while a tyre is soft, or every time the driver returns to Home, teaches them to
 * ignore it — and the one time it matters they will. So the set of announced faults is held for
 * the life of the process, not in a composable: HomeFragment is recreated on every tab switch,
 * and state kept there would re-announce the same fault all afternoon.
 *
 * A fault that clears is forgotten, so it can announce again if it comes back — that is a new
 * event and worth hearing.
 *
 * Several arriving together get one beep, not one each: eight beeps says nothing more than one
 * does, and takes eight times as long to stop saying it.
 *
 * WHAT IT PLAYS
 * -------------
 * [ToneGenerator] rather than an audio asset, because the platform already has these tones and
 * shipping a .wav to say "beep" is a strange thing to do. Two beeps for a critical, one for a
 * caution: a driver should be able to tell those apart without looking at the screen, which is
 * the entire point of making a sound rather than only drawing something.
 */
object FaultTone {

    private const val TAG = "MotorGuardDiag"

    /** ~70% — audible over road noise without being the loudest thing in the cabin. */
    private const val VOLUME = 70

    private const val BEEP_MS = 140

    /** Faults already announced, so an ongoing one stays quiet. */
    private val announced = mutableSetOf<Hotspot>()

    /**
     * @param faults every hotspot currently faulted, with its severity.
     */
    @Synchronized
    fun onFaults(faults: Map<Hotspot, Severity>) {
        val current = faults.keys
        val fresh = current - announced

        // Drop anything that has cleared, so its return is a new event.
        announced.retainAll(current)
        if (fresh.isEmpty()) return
        announced += fresh

        val worst = fresh.mapNotNull { faults[it] }.maxByOrNull { it.ordinal } ?: return
        play(worst)
        Log.i(TAG, "fault tone for ${fresh.joinToString { it.name }} ($worst)")
    }

    /** Forget everything, so the next fault announces again. For tests and a data reset. */
    @Synchronized
    fun reset() = announced.clear()

    private fun play(severity: Severity) {
        runCatching {
            // Built per beep and released after. Holding one open keeps an audio session alive
            // for a sound played a handful of times a day, and on this board the output is not
            // something to occupy for no reason.
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
            val type = if (severity == Severity.CRITICAL) {
                ToneGenerator.TONE_PROP_BEEP2   // two short beeps
            } else {
                ToneGenerator.TONE_PROP_BEEP    // one
            }
            tone.startTone(type, BEEP_MS)
            // Release after it has finished; releasing immediately cuts the tone off.
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { tone.release() } },
                (BEEP_MS + RELEASE_GRACE_MS).toLong(),
            )
        }.onFailure { Log.w(TAG, "fault tone unavailable", it) }
    }

    /** Long enough for TONE_PROP_BEEP2's second beep and its gap to finish. */
    private const val RELEASE_GRACE_MS = 400
}
