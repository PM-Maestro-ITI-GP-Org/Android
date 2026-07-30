package com.motorguard.ivi.ui.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Wake-word detection, behind a port so the engine can be swapped.
 *
 * Two implementations ship:
 *  - [OnnxWakeWordDetector] — real always-on openWakeWord model (needs 3 .onnx files
 *    in assets/wakeword/, see cpp/README-native.md).
 *  - [DisabledWakeWordDetector] — no-op. Used automatically when the models are
 *    absent, so the build always runs and the mic button still works.
 */
interface WakeWordDetector {

    /** Frames of 16 kHz mono PCM are fed in; [onDetected] fires on a trigger. */
    fun start(onDetected: () -> Unit): Boolean
    fun stop()
    val isRunning: Boolean

    /** Last score seen, for tuning/telemetry (0f when unknown). */
    val lastScore: Float
}

/** Present so the app is functional before any model is dropped in. */
class DisabledWakeWordDetector(private val reason: String) : WakeWordDetector {
    override fun start(onDetected: () -> Unit): Boolean {
        Log.w("MotorGuardVoice", "wake word disabled: $reason")
        return false
    }
    override fun stop() {}
    override val isRunning = false
    override val lastScore = 0f
}

/**
 * openWakeWord, running on ONNX Runtime.
 *
 * The pipeline is three chained models, exactly as the Python library does it:
 *
 *   audio (16 kHz int16)
 *     → melspectrogram.onnx   → mel frames (32 bins)
 *     → embedding_model.onnx  → 96-d embedding per 76-frame window
 *     → <your>.onnx           → score 0..1
 *
 * Only the last model is specific to the phrase; the first two are shared and come
 * from the openWakeWord release. Put all three in `assets/wakeword/`.
 *
 * ⚠ UNVERIFIED ON DEVICE. The tensor shapes and the mel scaling below match
 * openWakeWord's reference implementation, but this Kotlin port has not been run
 * against real models yet. On first run it logs every model's actual input/output
 * shape — compare those to the constants here, and sanity-check the scores against
 * the Python `test_wake.py` on the same WAV before trusting it.
 */
class OnnxWakeWordDetector(
    private val context: Context,
    private val modelAsset: String = "wakeword/$WAKE_MODEL_FILE",
    private val threshold: Float = 0.8f,
    private val sampleRate: Int = 16_000,
) : WakeWordDetector {

    companion object {
        private const val TAG = "MotorGuardVoice"

        /** Swap this when the phrase changes (see docs/07-voice-implementation.md). */
        const val WAKE_MODEL_FILE = "hey_vega.onnx"
        private const val MEL_MODEL = "wakeword/melspectrogram.onnx"
        private const val EMB_MODEL = "wakeword/embedding_model.onnx"

        // openWakeWord reference geometry.
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76     // mel frames per embedding
        private const val EMB_DIM = 96
        private const val EMB_HISTORY = 16    // embeddings per wake-word inference
        private const val CHUNK = 1280        // 80 ms at 16 kHz

        /** True when all three models are present, so we can pick an implementation. */
        fun modelsPresent(context: Context): Boolean = runCatching {
            val names = context.assets.list("wakeword")?.toSet() ?: emptySet()
            listOf(WAKE_MODEL_FILE, "melspectrogram.onnx", "embedding_model.onnx")
                .all { it in names }
        }.getOrDefault(false)
    }

    // ORT types are referenced reflectively-free but kept nullable so a missing
    // dependency or model surfaces as a clean failure, never a crash at boot.
    private var env: ai.onnxruntime.OrtEnvironment? = null
    private var melSession: ai.onnxruntime.OrtSession? = null
    private var embSession: ai.onnxruntime.OrtSession? = null
    private var wakeSession: ai.onnxruntime.OrtSession? = null

    private var recorder: android.media.AudioRecord? = null
    private var thread: Thread? = null

    @Volatile override var isRunning = false
        private set

    @Volatile override var lastScore = 0f
        private set

    private val melBuffer = ArrayDeque<FloatArray>()   // rolling mel frames
    private val embBuffer = ArrayDeque<FloatArray>()   // rolling embeddings

    override fun start(onDetected: () -> Unit): Boolean {
        if (isRunning) return true
        if (!loadModels()) return false
        if (!openMic()) { closeModels(); return false }

        isRunning = true
        thread = Thread({ loop(onDetected) }, "wake-word").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        Log.i(TAG, "wake word listening (threshold=$threshold)")
        return true
    }

    override fun stop() {
        isRunning = false
        thread?.join(500)
        thread = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        closeModels()
        melBuffer.clear()
        embBuffer.clear()
    }

    // --- setup -------------------------------------------------------------

    private fun loadModels(): Boolean = runCatching {
        env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val opts = ai.onnxruntime.OrtSession.SessionOptions().apply {
            // One thread each: this runs continuously and must not starve the UI.
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        melSession = env!!.createSession(assetToFile(MEL_MODEL).absolutePath, opts)
        embSession = env!!.createSession(assetToFile(EMB_MODEL).absolutePath, opts)
        wakeSession = env!!.createSession(assetToFile(modelAsset).absolutePath, opts)

        // First-run diagnostics: compare these to the constants above.
        logShapes("melspectrogram", melSession!!)
        logShapes("embedding", embSession!!)
        logShapes("wakeword", wakeSession!!)
        true
    }.getOrElse {
        Log.e(TAG, "could not load wake-word models — falling back to mic button", it)
        closeModels()
        false
    }

    private fun logShapes(name: String, s: ai.onnxruntime.OrtSession) = runCatching {
        s.inputInfo.forEach { (k, v) -> Log.i(TAG, "$name input  $k = ${v.info}") }
        s.outputInfo.forEach { (k, v) -> Log.i(TAG, "$name output $k = ${v.info}") }
    }

    /** ORT wants a real path, so assets are copied out once into cacheDir. */
    private fun assetToFile(asset: String): File {
        val out = File(context.cacheDir, asset.substringAfterLast('/'))
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(asset).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    private fun closeModels() {
        runCatching { melSession?.close() }; melSession = null
        runCatching { embSession?.close() }; embSession = null
        runCatching { wakeSession?.close() }; wakeSession = null
        env = null
    }

    @Suppress("MissingPermission") // RECORD_AUDIO is declared; granted by the platform
    private fun openMic(): Boolean = runCatching {
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        recorder = android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK * 4),
        )
        if (recorder!!.state != android.media.AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord did not initialise (mic permission / in use?)")
            return false
        }
        recorder!!.startRecording()
        true
    }.getOrElse { Log.e(TAG, "could not open microphone", it); false }

    // --- the loop ----------------------------------------------------------

    private fun loop(onDetected: () -> Unit) {
        val pcm = ShortArray(CHUNK)
        val floats = FloatArray(CHUNK)
        var cooldownUntil = 0L

        while (isRunning) {
            val read = recorder?.read(pcm, 0, CHUNK) ?: -1
            if (read <= 0) continue

            // openWakeWord's melspectrogram model takes raw int16 magnitudes as
            // float (NOT normalised to ±1).
            for (i in 0 until read) floats[i] = pcm[i].toFloat()

            val score = runCatching { score(floats, read) }.getOrElse {
                Log.e(TAG, "inference failed; stopping wake word", it); -1f
            }
            if (score < 0f) break
            if (score > 0f) lastScore = score

            if (score >= threshold && System.currentTimeMillis() > cooldownUntil) {
                Log.i(TAG, "wake word detected (${"%.2f".format(score)})")
                // 2 s cooldown so one utterance can't fire the session repeatedly.
                cooldownUntil = System.currentTimeMillis() + 2_000
                embBuffer.clear()
                onDetected()
            }
        }
    }

    /** Returns the wake-word score for this chunk, or 0f if not enough history yet. */
    private fun score(audio: FloatArray, length: Int): Float {
        val env = env ?: return 0f

        // 1. audio -> mel frames
        val melIn = ai.onnxruntime.OnnxTensor.createTensor(
            env, FloatBuffer.wrap(audio, 0, length), longArrayOf(1, length.toLong()))
        val melOut = melIn.use { melSession!!.run(mapOf(melSession!!.inputNames.first() to it)) }
        melOut.use {
            val raw = it[0].value
            flattenMel(raw).forEach { frame -> melBuffer.addLast(frame) }
        }
        while (melBuffer.size > EMB_WINDOW * 4) melBuffer.removeFirst()
        if (melBuffer.size < EMB_WINDOW) return 0f

        // 2. mel window -> embedding
        val window = melBuffer.toList().takeLast(EMB_WINDOW)
        val embInput = FloatArray(EMB_WINDOW * MEL_BINS)
        window.forEachIndexed { f, frame ->
            for (b in 0 until MEL_BINS) embInput[f * MEL_BINS + b] = frame.getOrElse(b) { 0f }
        }
        val embIn = ai.onnxruntime.OnnxTensor.createTensor(
            env, FloatBuffer.wrap(embInput),
            longArrayOf(1, EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1))
        val embOut = embIn.use { embSession!!.run(mapOf(embSession!!.inputNames.first() to it)) }
        embOut.use { embBuffer.addLast(flattenEmbedding(it[0].value)) }
        while (embBuffer.size > EMB_HISTORY) embBuffer.removeFirst()
        if (embBuffer.size < EMB_HISTORY) return 0f

        // 3. embedding history -> score
        val wakeInput = FloatArray(EMB_HISTORY * EMB_DIM)
        embBuffer.forEachIndexed { i, emb ->
            for (d in 0 until EMB_DIM) wakeInput[i * EMB_DIM + d] = emb.getOrElse(d) { 0f }
        }
        val wakeIn = ai.onnxruntime.OnnxTensor.createTensor(
            env, FloatBuffer.wrap(wakeInput),
            longArrayOf(1, EMB_HISTORY.toLong(), EMB_DIM.toLong()))
        return wakeIn.use { input ->
            wakeSession!!.run(mapOf(wakeSession!!.inputNames.first() to input)).use { out ->
                firstFloat(out[0].value)
            }
        }
    }

    // --- tensor unwrapping -------------------------------------------------
    // ORT hands back nested arrays whose exact nesting depends on the model's
    // rank, so these walk whatever comes out rather than assuming one shape.

    /** Mel output is [1,1,T,32] in the reference model; emit T frames of 32. */
    private fun flattenMel(value: Any?): List<FloatArray> {
        val flat = collectFloats(value)
        if (flat.isEmpty()) return emptyList()
        val frames = flat.size / MEL_BINS
        return (0 until frames).map { f ->
            FloatArray(MEL_BINS) { b ->
                // openWakeWord applies this scaling before the embedding model.
                flat[f * MEL_BINS + b] / 10f + 2f
            }
        }
    }

    private fun flattenEmbedding(value: Any?): FloatArray {
        val flat = collectFloats(value)
        return FloatArray(EMB_DIM) { flat.getOrElse(it) { 0f } }
    }

    private fun firstFloat(value: Any?): Float = collectFloats(value).firstOrNull() ?: 0f

    private fun collectFloats(value: Any?): FloatArray {
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
}

/** Picks the real detector when the models are present, the no-op when they aren't. */
fun createWakeWordDetector(context: Context): WakeWordDetector =
    if (OnnxWakeWordDetector.modelsPresent(context)) {
        OnnxWakeWordDetector(context)
    } else {
        DisabledWakeWordDetector(
            "no models in assets/wakeword/ — use the rail mic button, " +
                "or see docs/07-voice-implementation.md")
    }
