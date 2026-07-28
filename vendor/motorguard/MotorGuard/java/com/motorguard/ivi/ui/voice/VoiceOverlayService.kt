// VoiceOverlayService — owner D
// NOT a fragment / NOT a tab. A system overlay, Google-Assistant style.
package com.motorguard.ivi.ui.voice

import android.service.voice.VoiceInteractionService

/**
 * FEATURES
 *  - Always-on wake word ("Hey Motor Guard") -> pops a bottom listen bar
 *  - Floats OVER whatever surface is showing; dismisses on completion
 *  - States: idle · listening · thinking · speaking (animated orb + waveform)
 *  - Live transcript of the utterance
 *  - Routes intents to the right tab (play music, navigate, climate, call)
 * REQUIRES: mic + AudioFocus, SYSTEM_ALERT_WINDOW / VoiceInteractionService,
 *           on-device STT + NLU
 * NOTE    : has no launcher icon; not user-launchable from the rail
 */
class VoiceOverlayService : VoiceInteractionService()
