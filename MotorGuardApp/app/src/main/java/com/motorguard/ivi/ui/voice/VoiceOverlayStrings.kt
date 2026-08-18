// VoiceOverlayStrings — owner D
// The handful of fixed phrases VoiceOverlaySession itself speaks -- recognizer
// failures, "didn't catch that."
//
// Always English: the assistant's spoken/shown output is English-only by
// design, even though STT input can be Egyptian Arabic (see VoiceLanguage's
// doc comment) -- so these never needed per-language variants to begin with.
package com.motorguard.ivi.ui.voice

object VoiceOverlayStrings {

    const val recognitionUnavailable: String = "Speech recognition isn't available on this build."

    const val didNotCatch: String = "Sorry, I didn't catch that."

    const val didNotHear: String = "I didn't hear anything."

    const val needsMicPermission: String = "I need microphone permission."

    const val recognitionFailed: String = "Speech recognition failed."

    const val couldNotStartListening: String = "Could not start listening."

    const val fallbackReply: String =
        "Sorry, I didn't catch that. You can ask me to explain a warning " +
            "light, whether it's serious, or where the nearest garage is."
}
