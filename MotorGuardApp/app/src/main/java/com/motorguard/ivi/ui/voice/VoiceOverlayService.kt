// VoiceOverlayService — owner D
// NOT a fragment / NOT a tab. A system overlay, Google-Assistant style.
package com.motorguard.ivi.ui.voice

import android.service.voice.VoiceInteractionService
import android.util.Log
import java.lang.ref.WeakReference

/**
 * The always-on half of the assistant. Android starts this at boot once the app is
 * the selected assistant, so it is the right place to host wake-word detection.
 *
 * FEATURES
 *  - Always-on wake word -> pops the bottom listen bar over any surface
 *  - Delegates the interaction itself to VoiceOverlaySession
 *  - States: idle · listening · thinking · speaking (see VoiceOverlayUi)
 *  - Routes intents to the right tab
 * REQUIRES: RECORD_AUDIO, BIND_VOICE_INTERACTION, on-device STT + TTS
 * NOTE    : no launcher icon; not user-launchable from the rail. The rail mic
 *           button asks the platform to show this session (see MainActivity).
 */
class VoiceOverlayService : VoiceInteractionService() {

    companion object {
        private const val TAG = "MotorGuardVoice"

        /**
         * Set while the platform has this service alive. Used by the rail mic
         * button: only a VoiceInteractionService may open its own session, so the
         * Activity asks the service rather than the other way round.
         */
        private var instance = WeakReference<VoiceOverlayService>(null)

        /** @return true if a session was shown. */
        fun requestSession(): Boolean {
            val svc = instance.get() ?: return false
            return runCatching { svc.showSession(null, 0); true }
                .onFailure { Log.e(TAG, "showSession failed", it) }
                .getOrDefault(false)
        }
    }

    private var wakeWord: WakeWordDetector? = null

    override fun onReady() {
        super.onReady()
        instance = WeakReference(this)
        // Warm the reasoning core now so the first utterance isn't slowed by
        // opening the database.
        VoiceEngine.ensureReady()
        startWakeWord()
        Log.i(TAG, "voice service ready (faults=${VoiceEngine.faultCount()})")
    }

    private fun startWakeWord() {
        val detector = createWakeWordDetector(this)
        val started = detector.start {
            // Fired off the audio thread — showSession is safe to call from here,
            // but keep the work tiny.
            runCatching { showSession(null, 0) }
                .onFailure { Log.e(TAG, "showSession failed", it) }
        }
        wakeWord = if (started) detector else null
        if (!started) {
            Log.w(TAG, "no wake word running — trigger with the rail mic button instead")
        }
    }

    override fun onShutdown() {
        instance = WeakReference<VoiceOverlayService>(null)
        wakeWord?.stop()
        wakeWord = null
        super.onShutdown()
    }
}
