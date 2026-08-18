// VoicePrefs — owner D
// The one setting the voice assistant has: which VoiceLanguage to speak and
// listen in. Read by every process-wide voice singleton (PiperTts,
// WhisperStt, the TTS/RecognitionService wrappers) and written by the IVI
// Settings screen.
package com.motorguard.ivi.ui.voice

import android.content.Context

object VoicePrefs {

    private const val PREFS_NAME = "motorguard_voice"
    private const val KEY_LANGUAGE = "language"

    fun getLanguage(context: Context): VoiceLanguage =
        VoiceLanguage.fromCode(prefs(context).getString(KEY_LANGUAGE, null))

    fun setLanguage(context: Context, language: VoiceLanguage) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
