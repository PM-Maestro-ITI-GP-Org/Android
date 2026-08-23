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
 *
 * The detector itself lives in WakeWordService, not here. This service is bound
 * by the platform rather than started, so it cannot call startForeground() on
 * itself -- and without a microphone-type foreground service, AudioPolicy
 * silences background capture and read() starves. See WakeWordService for the
 * full reasoning.
 */
class VoiceOverlayService : VoiceInteractionService() {

    companion object {
        private const val TAG = "MotorGuardVoice"

        /**
         * Set while the platform has this service alive. Used by the rail mic
         * button and by the wake-word detector: only a VoiceInteractionService
         * may open its own session, so callers ask the service rather than the
         * other way round.
         */
        private var instance = WeakReference<VoiceOverlayService>(null)

        /** @return true if a session was shown. */
        fun requestSession(): Boolean {
            val svc = instance.get() ?: return false
            return runCatching { svc.showSession(null, 0); true }
                .onFailure { Log.e(TAG, "showSession failed", it) }
                .getOrDefault(false)
        }

        /**
         * Release the wake-word mic so the active session's SpeechRecognizer can use
         * it. The session calls this on show and [resumeWakeWord] on hide — otherwise
         * the always-on recorder and the recognizer contend and STT hears nothing.
         */
        fun pauseWakeWord() = WakeWordService.pause()

/**
         * Reclaim the mic for wake-word listening after a session closes.
         *
         * No detection handler to supply here: [WakeWordService.resume] re-arms the
         * detector's own pause()/resume() pair, which keeps the callback installed at
         * [WakeWordService.onStartCommand] across the pause rather than needing it
         * re-supplied on every hand-off.
         */
        fun resumeWakeWord() = WakeWordService.resume()
    }

    override fun onReady() {
        super.onReady()
        instance = WeakReference(this)
        // Warm the reasoning core now so the first utterance isn't slowed by
        // opening the database.
        VoiceEngine.ensureReady()
        WakeWordService.start(this)
        Log.i(TAG, "voice service ready (faults=${VoiceEngine.faultCount()})")
    }

    override fun onShutdown() {
        instance = WeakReference<VoiceOverlayService>(null)
        WakeWordService.stop(this)
        super.onShutdown()
    }
}