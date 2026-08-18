// PiperTtsService — owner D
// Exposes Piper as a system TTS engine.
package com.motorguard.ivi.ui.voice

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log

/**
 * A TextToSpeechService that speaks English through PiperTts (neural,
 * on-device synthesis).
 *
 * Implementing the platform interface rather than calling PiperTts directly
 * is deliberate: VoiceOverlaySession already uses
 * android.speech.tts.TextToSpeech, so once this engine is registered that
 * existing code starts working with no changes at all, and anything else on
 * the device gets speech too.
 *
 * Stock AAOS ships no TTS engine, which is why the session logged
 * "TTS unavailable; overlay will show text only" until this landed.
 *
 * Always English, regardless of [VoicePrefs]'s language (which only affects
 * STT input -- see VoiceLanguage's doc comment) or the lang/country the
 * caller passes in. This engine used to also speak Egyptian Arabic through
 * pre-rendered clips (see git history / ArabicClipTts.kt, now unused but
 * left on disk) when Arabic was selected; that path was removed so replies
 * are English-only no matter what language the driver spoke in.
 *
 * Register it after install:
 *   settings --user 10 put secure tts_default_synth com.motorguard.ivi
 */
class PiperTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "MotorGuardVoice"

        /**
         * Split on sentence-ending punctuation followed by whitespace. The
         * lookbehind keeps the punctuation attached, which matters: Piper uses
         * it for prosody, and stripping it makes every sentence end flat.
         */
        private val SENTENCE = Regex("(?<=[.!?])\\s+")
    }

    @Volatile private var stopped = false

    override fun onCreate() {
        super.onCreate()
        // Loading the VITS model and espeak's data takes a moment; do it now
        // so the first utterance is not delayed.
        Thread({ PiperTts.ensureReady(filesDir) }, "piper-load").start()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val locale = VoiceLanguage.ENGLISH.locale
        if (lang != locale.isO3Language) return TextToSpeech.LANG_NOT_SUPPORTED
        return if (country == locale.isO3Country) TextToSpeech.LANG_COUNTRY_AVAILABLE
        else TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> {
        val locale = VoiceLanguage.ENGLISH.locale
        return arrayOf(locale.isO3Language, locale.isO3Country, "")
    }

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopped = false

        val sampleRate = PiperTts.sampleRate

        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        if (!PiperTts.ensureReady(filesDir)) {
            Log.e(TAG, "tts requested but Piper is not loaded")
            callback.error()
            return
        }
        val synthesize: (String) -> ShortArray = { sentence -> PiperTts.synthesize(sentence) }

        // Piper generates a whole utterance before any audio exists, so a
        // multi-sentence reply means a long silence with nothing playing --
        // measured at ~1.4 s for a three-sentence answer. Splitting on sentence
        // boundaries and delivering each as it is ready means playback starts
        // after the FIRST sentence while the rest is still being synthesised.
        //
        // The total work is the same; what changes is when the user hears
        // something.
        val sentences = text.split(SENTENCE).filter { it.isNotBlank() }
        if (sentences.isEmpty()) {
            callback.error()
            return
        }

        if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            callback.error()
            return
        }

        // The framework accepts audio in bounded chunks; anything larger than
        // getMaxBufferSize() is rejected outright. Two bytes per sample, and an
        // odd split would tear a sample in half.
        val samplesPerChunk = (callback.maxBufferSize / 2).coerceAtLeast(1)
        var spoke = false

        for (sentence in sentences) {
            if (stopped) return

            val pcm = synthesize(sentence)
            if (pcm.isEmpty()) continue
            spoke = true

            var offset = 0
            while (offset < pcm.size) {
                if (stopped) return
                val count = minOf(samplesPerChunk, pcm.size - offset)
                val bytes = ByteArray(count * 2)
                for (i in 0 until count) {
                    val v = pcm[offset + i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()              // little endian
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                if (callback.audioAvailable(bytes, 0, bytes.size) != TextToSpeech.SUCCESS) {
                    callback.error()
                    return
                }
                offset += count
            }
        }

        if (!spoke) {
            callback.error()
            return
        }
        callback.done()
    }
}