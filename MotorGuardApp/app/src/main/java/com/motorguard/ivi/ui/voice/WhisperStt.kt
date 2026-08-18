// WhisperStt — owner D
// Kotlin face of whisper_jni.cpp.
package com.motorguard.ivi.ui.voice

import android.util.Log
import java.io.File

/**
 * Offline speech-to-text via whisper.cpp.
 *
 * Trade-off against Vosk, which this replaces in VoskRecognitionService:
 *
 *  + Much stronger acoustic model. Vosk's small model transcribed "P0217" as
 *    "pee zero to one southern" -- the English words were perfect, only the
 *    alphanumeric code failed, because a general language model has no reason to
 *    expect letter-digit sequences.
 *  + initial_prompt conditioning. Priming with real DTC codes biases decoding
 *    toward that shape. This is the actual reason to switch; raw accuracy alone
 *    would not justify the integration cost.
 *  - No streaming. Whisper processes a complete utterance in one pass, so there
 *    are no partial results and the overlay stays blank until the user stops
 *    talking. Expect roughly 1-3x realtime for tiny.en on this board.
 *
 * One model per [VoiceLanguage]:
 *  - English: ggml-tiny.en.bin (~75 MB) or ggml-base.en.bin (~142 MB), the
 *    English-only variants -- smaller and slightly more accurate than the
 *    multilingual ones for English-only audio. From
 *    https://huggingface.co/ggerganov/whisper.cpp.
 *  - Arabic (Egypt): a *multilingual* whisper-small architecture with
 *    decoding forced to "ar" via whisperLanguage (whisper.cpp has no
 *    separate dialect code -- "ar" is still macro-language Arabic as far as
 *    the decoder is concerned). The weights themselves are fine-tuned for
 *    Egyptian dialect, from
 *    https://huggingface.co/IbrahimAmin/code-switched-egyptian-arabic-whisper-small
 *    (Apache 2.0; fine-tuned from openai/whisper-small on code-switched
 *    Egyptian Arabic/English audio -- WER on ESCWA spontaneous speech
 *    98%->45% vs. stock whisper-small). Converted HF safetensors -> ggml
 *    with whisper.cpp/models/convert-h5-to-ggml.py, then quantized to q5_0
 *    with whisper-quantize to land at the same ~175 MB footprint as the
 *    macro-Arabic model it replaced.
 *
 *    Gotcha: this vendored whisper.cpp checkout's quantize tool leaves 3D
 *    conv weights (encoder.conv1/conv2) at F32, but the loader always
 *    expects F16 for those once a model is quantized (see the `vtype`
 *    ternary in src/whisper.cpp) -- a stock quantize run produces a file
 *    that fails to load ("wrong size in model file"). Patched
 *    examples/common-ggml.cpp locally to downcast those two tensors to F16
 *    before writing; that patch is only in this local checkout (the
 *    whisper.cpp dir is gitignored) so redo it if the checkout is
 *    re-cloned.
 *
 * Pushed rather than bundled so a model can be swapped without a rebuild --
 * see scripts/push_whisper_ar.sh, or by hand:
 *
 *   adb push ggml-tiny.en.bin /data/local/tmp/
 *   adb shell su 0 cp /data/local/tmp/ggml-tiny.en.bin \
 *       /data/user/10/com.motorguard.ivi/files/whisper_en.bin
 *   adb push whisper_ar.bin /data/local/tmp/
 *   adb shell su 0 cp /data/local/tmp/whisper_ar.bin \
 *       /data/user/10/com.motorguard.ivi/files/whisper_ar.bin
 *   adb shell su 0 chown u10_a103:u10_a103 \
 *       /data/user/10/com.motorguard.ivi/files/whisper_*
 *   adb shell su 0 restorecon -R /data/user/10/com.motorguard.ivi/files/
 */
object WhisperStt {

    private const val TAG = "MotorGuardVoice"

    /**
     * Biases the decoder. Real codes, not placeholders: Whisper conditions on
     * this text as if it preceded the audio, so concrete examples of the format
     * you expect are worth far more than a description of it. One per
     * language, since the prompt itself is spoken text the model conditions
     * on -- an English prompt does nothing useful ahead of Arabic audio.
     */
    private val PROMPTS = mapOf(
        VoiceLanguage.ENGLISH to
            "Vehicle diagnostics. Codes like P0217, P0300, P0420, B1000, C1201, U0100. " +
                "Coolant temperature sensor, oil pressure, mass air flow, catalytic converter.",
        // Includes the intent-matching vocabulary itself (see table_ar_ in
        // ScoringIntentMatcher.cpp), not just DTC/sensor terms: biasing
        // decoding toward "لمبة"/"ورشة"/"خطير" etc. matters as much as
        // getting the code number right, since a misheard trigger word is an
        // Unknown intent even when the rest of the sentence came through.
        VoiceLanguage.ARABIC_EGYPT to
            "تشخيص أعطال السيارة. أكواد زي P0217, P0300, P0420, B1000, C1201, U0100. " +
                "حساس حرارة المياه، ضغط الزيت، حساس الهواء، الكاتالييزر. " +
                "لمبة تحذير، إيه معناها، فيه عطل. ده خطير؟ أقدر أكمل سواقة؟ لازم أوقف؟ " +
                "فين أقرب ورشة؟ محتاج ميكانيكي. فيه مشاكل تانية؟",
    )

    /** Whisper is CPU-bound; the Pi 5 has four cores and the UI needs one. */
    private const val THREADS = 4

    @Volatile private var loadedLanguage: VoiceLanguage? = null

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeTranscribe(
        pcm: FloatArray, prompt: String, threads: Int, language: String,
    ): String
    private external fun nativeRelease()

    init {
        runCatching { System.loadLibrary("motorguardvoice") }
            .onFailure { Log.e(TAG, "could not load native library", it) }
    }

    /** Safe to call repeatedly; the native side reloads only when [language] changes. */
    @Synchronized
    fun ensureReady(filesDir: File, language: VoiceLanguage): Boolean {
        if (loadedLanguage == language) return true

        val model = ModelPaths.file(filesDir, language.whisperModelFile)
        if (model == null) {
            Log.e(TAG, "whisper model missing for ${language.code}")
            return false
        }
        // nativeInit replaces whatever context (if any) is already loaded --
        // see whisper_jni.cpp -- so switching languages is just calling it again.
        val loaded = runCatching { nativeInit(model.absolutePath) }
            .onFailure { Log.e(TAG, "whisper init threw", it) }
            .getOrDefault(false)
        loadedLanguage = if (loaded) language else null
        return loaded
    }

    /**
     * @param pcm    16 kHz mono, 16-bit signed
     * @param length valid samples in [pcm]
     */
    fun transcribe(pcm: ShortArray, length: Int, language: VoiceLanguage): String {
        if (loadedLanguage != language) return ""
        if (length <= 0) return ""

        // whisper.cpp wants float32 normalised to [-1, 1].
        val f = FloatArray(length)
        for (i in 0 until length) f[i] = pcm[i] / 32768.0f

        val prompt = PROMPTS[language].orEmpty()
        val started = System.currentTimeMillis()
        val text = runCatching { nativeTranscribe(f, prompt, THREADS, language.whisperLanguage) }
            .onFailure { Log.e(TAG, "whisper transcribe threw", it) }
            .getOrDefault("")
        val ms = System.currentTimeMillis() - started
        val audioMs = length * 1000L / 16_000
        Log.i(TAG, "whisper: ${audioMs}ms audio in ${ms}ms (%.1fx realtime)"
            .format(if (ms > 0) audioMs.toDouble() / ms else 0.0))
        return text
    }

    @Synchronized
    fun release() {
        if (loadedLanguage == null) return
        runCatching { nativeRelease() }
        loadedLanguage = null
    }
}