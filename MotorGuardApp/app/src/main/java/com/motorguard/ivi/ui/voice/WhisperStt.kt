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
 * Model: ggml-tiny.en.bin (~75 MB) or ggml-base.en.bin (~142 MB) from
 * https://huggingface.co/ggerganov/whisper.cpp. Pushed rather than bundled so it
 * can be swapped without a rebuild:
 *
 *   adb push ggml-tiny.en.bin /data/local/tmp/
 *   adb shell su 0 cp /data/local/tmp/ggml-tiny.en.bin \
 *       /data/user/10/com.motorguard.ivi/files/whisper.bin
 *   adb shell su 0 chown u10_a103:u10_a103 \
 *       /data/user/10/com.motorguard.ivi/files/whisper.bin
 *   adb shell su 0 restorecon /data/user/10/com.motorguard.ivi/files/whisper.bin
 */
object WhisperStt {

    private const val TAG = "MotorGuardVoice"
    const val MODEL_FILE = "whisper.bin"

    /**
     * Biases the decoder. Real codes, not placeholders: Whisper conditions on this text as if it
     * preceded the audio, so concrete examples of the format you expect are worth far more than
     * a description of it.
     *
     * Which cuts both ways, and did. This used to prime with P0217, P0300, B1000 and friends,
     * plus coolant temperature, oil pressure and the catalytic converter — a vocabulary this
     * vehicle does not have and, since the catalogue was cut back to the E codes, one the
     * assistant cannot answer about either. Whisper was being told to expect "P0217" from a
     * driver saying "E-31", and a decoder primed for the wrong two letters will find them.
     *
     * So it now primes with what is actually said to this car: the three cluster codes, and one
     * example of each shape of command. Keep it that way — every phrase in here is a thumb on
     * the scale, and a phrase the assistant no longer understands is a thumb on the wrong side.
     */
    private const val PROMPT =
        "Vehicle assistant. Fault codes on the cluster: E-01, E-21, E-31. " +
            "Is the motor fault electrical or mechanical. How long has the motor got left. " +
            "Where is the nearest petrol station, car centre, charging station. " +
            "What's playing, skip this song, turn it up, night mode, cancel the route."

    /** Whisper is CPU-bound; the Pi 5 has four cores and the UI needs one. */
    private const val THREADS = 4

    @Volatile private var ready = false

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeTranscribe(pcm: FloatArray, prompt: String, threads: Int): String
    private external fun nativeRelease()

    init {
        runCatching { System.loadLibrary("motorguardvoice") }
            .onFailure { Log.e(TAG, "could not load native library", it) }
    }

    /** Safe to call repeatedly; the native side loads at most once. */
    @Synchronized
    fun ensureReady(filesDir: File): Boolean {
        if (ready) return true
        val model = ModelPaths.file(filesDir, MODEL_FILE)
        if (model == null) {
            Log.e(TAG, "whisper model missing")
            return false
        }
        ready = runCatching { nativeInit(model.absolutePath) }
            .onFailure { Log.e(TAG, "whisper init threw", it) }
            .getOrDefault(false)
        return ready
    }

    /**
     * @param pcm    16 kHz mono, 16-bit signed
     * @param length valid samples in [pcm]
     */
    fun transcribe(pcm: ShortArray, length: Int): String {
        if (!ready) return ""
        if (length <= 0) return ""

        // whisper.cpp wants float32 normalised to [-1, 1].
        val f = FloatArray(length)
        for (i in 0 until length) f[i] = pcm[i] / 32768.0f

        val started = System.currentTimeMillis()
        val text = runCatching { nativeTranscribe(f, PROMPT, THREADS) }
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
        if (!ready) return
        runCatching { nativeRelease() }
        ready = false
    }
}