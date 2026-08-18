// VoiceLanguage — owner D
// The set of speech-input languages a driver can pick in IVI Settings.
package com.motorguard.ivi.ui.voice

import java.util.Locale

/**
 * One STT input profile: which Whisper model listens and in which language,
 * and the Android locale SpeechRecognizer intents are told about.
 *
 * This governs input only. Output (replies, TTS) is always English,
 * regardless of which VoiceLanguage is selected -- see PiperTts (one voice,
 * always English) and VoiceOverlaySession's `VoiceEngine.handle(utterance,
 * VoiceLanguage.ENGLISH)`. Egyptian Arabic used to also drive an Arabic
 * reply voice (pre-rendered clips, see git history / ArabicClipTts.kt, now
 * unused but left on disk); that path was deliberately removed so a driver
 * can speak Egyptian Arabic and always get an English answer back.
 *
 * Adding an input language means adding a case here plus pushing its model
 * file under [whisperModelFile] via [ModelPaths] -- nothing else in the STT
 * path names a language directly.
 */
enum class VoiceLanguage(
    /** Persisted in [VoicePrefs]; keep stable once shipped. */
    val code: String,
    val label: String,
    val whisperModelFile: String,
    /** ISO-639-1 code passed to whisper.cpp's decoder. */
    val whisperLanguage: String,
    val locale: Locale,
) {
    ENGLISH(
        code = "en",
        label = "English",
        whisperModelFile = "whisper_en.bin",
        whisperLanguage = "en",
        locale = Locale.US,
    ),

    /**
     * Egyptian-accented Arabic speech input.
     *
     * Whisper is multilingual for "ar" (macro-language Arabic; whisper.cpp's
     * decoder has no separate Egyptian dialect code) -- use the multilingual
     * ggml-*.bin models, not a *.en one. The Arabic weights themselves are
     * an Egyptian-dialect fine-tune though; see WhisperStt's model-loading
     * doc comment for provenance.
     */
    ARABIC_EGYPT(
        code = "ar",
        label = "العربية (مصر)",
        whisperModelFile = "whisper_ar.bin",
        whisperLanguage = "ar",
        locale = Locale("ar", "EG"),
    );

    companion object {
        val DEFAULT = ENGLISH

        fun fromCode(code: String?): VoiceLanguage = entries.find { it.code == code } ?: DEFAULT
    }
}
