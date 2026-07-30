package com.motorguard.ivi.ui.voice

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Manual trigger for the overlay — the rail mic button.
 *
 * The wake word is the intended path; this exists so the assistant is testable
 * before a model is in place, and as a fallback in a noisy cabin.
 */
object VoiceTrigger {

    private const val TAG = "MotorGuardVoice"

    fun show(context: Context) {
        // Preferred: ask our own VoiceInteractionService to open a session.
        if (VoiceOverlayService.requestSession()) return

        // Not running as the selected assistant yet — ask the platform instead.
        val assist = Intent(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(assist); true }
            .onFailure { Log.w(TAG, "ACTION_ASSIST not handled", it) }
            .getOrDefault(false)

        if (!launched) {
            Toast.makeText(
                context,
                "Voice assistant isn't the selected assistant yet — see docs/07-voice-implementation.md",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
