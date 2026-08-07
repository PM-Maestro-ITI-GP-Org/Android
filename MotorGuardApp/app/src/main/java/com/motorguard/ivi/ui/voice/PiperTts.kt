// PiperTts — owner D
// Neural text-to-speech: espeak-ng phonemises, a Piper VITS model synthesises.
package com.motorguard.ivi.ui.voice

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Piper on ONNX Runtime.
 *
 * Pipeline:
 *   text -> espeak-ng (native, espeak_jni.cpp) -> IPA phonemes
 *        -> phoneme_id_map from the voice's .onnx.json
 *        -> VITS model -> float audio at the voice's sample rate
 *
 * Inference runs here rather than in C++ because onnxruntime-android is already
 * a dependency for the wake word; building native ORT as well would double the
 * binary for no benefit.
 *
 * Two files per voice, pushed rather than bundled (they are ~30 MB and swapping
 * voices should not need a rebuild):
 *
 *   adb push en_US-lessac-medium.onnx      /data/local/tmp/
 *   adb push en_US-lessac-medium.onnx.json /data/local/tmp/
 *   adb shell su 0 cp /data/local/tmp/en_US-lessac-medium.onnx \
 *       /data/user/10/com.motorguard.ivi/files/piper.onnx
 *   adb shell su 0 cp /data/local/tmp/en_US-lessac-medium.onnx.json \
 *       /data/user/10/com.motorguard.ivi/files/piper.json
 *   adb shell su 0 chown u10_a103:u10_a103 \
 *       /data/user/10/com.motorguard.ivi/files/piper.*
 *   adb shell su 0 restorecon -R /data/user/10/com.motorguard.ivi/files/
 *
 * espeak-ng's data directory also has to be on disk; see espeakDataDir below.
 * Voices: https://huggingface.co/rhasspy/piper-voices
 */
object PiperTts {

    private const val TAG = "MotorGuardVoice"

    const val MODEL_FILE = "piper.onnx"
    const val CONFIG_FILE = "piper.json"
    const val ESPEAK_DIR = "espeak"        // contains espeak-ng-data/

    /** Piper's defaults; the config can override them per voice. */
    private const val DEFAULT_NOISE = 0.667f
    private const val DEFAULT_LENGTH = 1.0f
    private const val DEFAULT_NOISE_W = 0.8f

    /** Inserted between every phoneme, and at both ends. Piper's convention. */
    private const val PAD = "_"
    private const val BOS = "^"
    private const val EOS = "$"

    private external fun nativeInitEspeak(dataPath: String): Boolean
    private external fun nativePhonemize(text: String, voice: String): String
    private external fun nativeReleaseEspeak()

    private var env: ai.onnxruntime.OrtEnvironment? = null
    private var session: ai.onnxruntime.OrtSession? = null
    private var phonemeIds: Map<String, Long> = emptyMap()
    private var espeakVoice = "en-us"

    var sampleRate = 22_050
        private set

    @Volatile private var ready = false

    init {
        runCatching { System.loadLibrary("motorguardvoice") }
            .onFailure { Log.e(TAG, "could not load native library", it) }
    }

    @Synchronized
    fun ensureReady(filesDir: File): Boolean {
        if (ready) return true

        val model = ModelPaths.file(filesDir, MODEL_FILE)
        val config = ModelPaths.file(filesDir, CONFIG_FILE)
        val espeak = ModelPaths.dir(filesDir, ESPEAK_DIR)
        if (model == null || config == null || espeak == null) {
            Log.e(TAG, "piper model or config missing in ${filesDir.absolutePath}")
            return false
        }
        if (!File(espeak, "espeak-ng-data").isDirectory) {
            Log.e(TAG, "espeak-ng-data missing under ${espeak.absolutePath}")
            return false
        }

        if (!runCatching { nativeInitEspeak(espeak.absolutePath) }
                .onFailure { Log.e(TAG, "espeak init threw", it) }
                .getOrDefault(false)
        ) return false

        if (!loadConfig(config)) return false

        ready = runCatching {
            env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val opts = ai.onnxruntime.OrtSession.SessionOptions().apply {
                // Leave a core for the wake word, which never stops running.
                setIntraOpNumThreads(3)
            }
            session = env!!.createSession(model.absolutePath, opts)
            Log.i(TAG, "piper loaded (${phonemeIds.size} phonemes, ${sampleRate}Hz)")
            true
        }.onFailure { Log.e(TAG, "piper model failed to load", it) }
            .getOrDefault(false)

        return ready
    }

    private fun loadConfig(config: File): Boolean = runCatching {
        val json = JSONObject(config.readText())

        sampleRate = json.optJSONObject("audio")?.optInt("sample_rate", 22_050) ?: 22_050
        espeakVoice = json.optJSONObject("espeak")?.optString("voice", "en-us") ?: "en-us"

        val map = json.getJSONObject("phoneme_id_map")
        val out = HashMap<String, Long>(map.length())
        for (key in map.keys()) {
            // Each entry is an array; Piper voices use one id per phoneme.
            val arr = map.getJSONArray(key)
            if (arr.length() > 0) out[key] = arr.getLong(0)
        }
        phonemeIds = out
        true
    }.onFailure { Log.e(TAG, "piper config unreadable", it) }.getOrDefault(false)

    /**
     * @return 16-bit PCM at [sampleRate], or an empty array on failure.
     */
    fun synthesize(text: String): ShortArray {
        if (!ready) return ShortArray(0)
        if (text.isBlank()) return ShortArray(0)

        val phonemes = runCatching { nativePhonemize(text, espeakVoice) }
            .getOrDefault("")
        if (phonemes.isBlank()) {
            Log.w(TAG, "piper: no phonemes for \"$text\"")
            return ShortArray(0)
        }

        val ids = toIds(phonemes)
        if (ids.size <= 2) {
            Log.w(TAG, "piper: no known phonemes in \"$phonemes\"")
            return ShortArray(0)
        }

        val started = System.currentTimeMillis()
        val audio = runCatching { infer(ids) }
            .onFailure { Log.e(TAG, "piper inference failed", it) }
            .getOrDefault(FloatArray(0))
        if (audio.isEmpty()) return ShortArray(0)

        // VITS outputs float in roughly [-1, 1]; scale and clip to PCM16.
        // VITS output rarely uses the full [-1, 1] range -- a flat 32767 scale
        // leaves most of the headroom unused, which is why the result sounds
        // quiet even at maximum stream and hardware volume. Normalise to the
        // actual peak of each utterance instead.
        var peak = 0f
        for (v in audio) {
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
        }
        // 0.95 leaves a little room so nothing clips at the very top.
        val gain = if (peak > 0.001f) (0.95f * 32767f / peak) else 32767f
        Log.d(TAG, "piper: peak=%.3f gain=%.0f".format(peak, gain))

        val pcm = ShortArray(audio.size)
        for (i in audio.indices) {
            val v = audio[i] * gain
            pcm[i] = when {
                v > 32767f -> 32767
                v < -32768f -> -32768
                else -> v.toInt().toShort()
            }
        }

        val ms = System.currentTimeMillis() - started
        val audioMs = pcm.size * 1000L / sampleRate
        Log.i(TAG, "piper: ${audioMs}ms audio in ${ms}ms")
        return pcm
    }

    /**
     * Piper interleaves a pad between every phoneme and brackets the sequence
     * with BOS/EOS. Getting this wrong produces audio that sounds almost right
     * but is subtly garbled, so it is worth matching upstream exactly.
     */
    private fun toIds(phonemes: String): LongArray {
        val out = ArrayList<Long>(phonemes.length * 2 + 3)
        phonemeIds[BOS]?.let { out.add(it) }
        phonemeIds[PAD]?.let { out.add(it) }

        // Iterate by code point: IPA is full of multi-byte characters.
        var i = 0
        while (i < phonemes.length) {
            val cp = phonemes.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += Character.charCount(cp)

            val id = phonemeIds[ch] ?: continue
            out.add(id)
            phonemeIds[PAD]?.let { out.add(it) }
        }

        phonemeIds[EOS]?.let { out.add(it) }
        return out.toLongArray()
    }

    private fun infer(ids: LongArray): FloatArray {
        val env = env ?: return FloatArray(0)
        val session = session ?: return FloatArray(0)

        val input = ai.onnxruntime.OnnxTensor.createTensor(
            env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
        val lengths = ai.onnxruntime.OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1))
        val scales = ai.onnxruntime.OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floatArrayOf(DEFAULT_NOISE, DEFAULT_LENGTH, DEFAULT_NOISE_W)),
            longArrayOf(3))

        val inputs = mapOf(
            "input" to input,
            "input_lengths" to lengths,
            "scales" to scales,
        )

        return input.use {
            lengths.use {
                scales.use {
                    session.run(inputs).use { out -> flatten(out[0].value) }
                }
            }
        }
    }

    /** Output is [1, 1, N]; the nesting depends on the export, so walk it. */
    private fun flatten(value: Any?): FloatArray {
        val out = ArrayList<Float>()
        fun walk(v: Any?) {
            when (v) {
                null -> {}
                is Float -> out.add(v)
                is FloatArray -> v.forEach { out.add(it) }
                is Array<*> -> v.forEach { walk(it) }
                else -> {}
            }
        }
        walk(value)
        return out.toFloatArray()
    }

    @Synchronized
    fun release() {
        if (!ready) return
        runCatching { session?.close() }; session = null
        env = null
        runCatching { nativeReleaseEspeak() }
        ready = false
    }
}