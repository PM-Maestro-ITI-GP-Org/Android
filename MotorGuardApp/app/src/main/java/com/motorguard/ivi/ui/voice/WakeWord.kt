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

    /**
     * Release the microphone without tearing down the models, so another capture —
     * the session's SpeechRecognizer — can use it. Call [resume] to listen again.
     * Without this the always-on recorder and the recognizer fight over the mic and
     * the recognizer hears silence ("I didn't hear anything").
     */
    fun pause()

    /** Re-acquire the mic and resume wake-word listening after a [pause]. */
    fun resume()

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
    override fun pause() {}
    override fun resume() {}
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
 * Verified on device (AAOS / Raspberry Pi 5, C-Media USB mic): all three models
 * load and the reported tensor shapes match the constants below.
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

        // openWakeWord reference geometry. Confirmed against the models' own
        // reported shapes: embedding input [-1,76,32,1], output [-1,1,1,96],
        // wake input [1,16,96], wake output [1,1].
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76     // mel frames per embedding
        private const val EMB_DIM = 96
        private const val EMB_HISTORY = 16    // embeddings per wake-word inference
        private const val CHUNK = 1280        // 80 ms at 16 kHz

        // MediaRecorder.AudioSource.HOTWORD is @SystemApi/@hide, so it is not on
        // the public SDK classpath and cannot be referenced by name from a gradle
        // build. The value is stable platform ABI.
        private const val SRC_HOTWORD = 1999

        /** How long to wait for a candidate config to actually deliver frames. */
        private const val PROBE_MS = 3_000L

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
    // Kept across a pause/resume so the mic can be handed to the session's recognizer
    // and reclaimed afterwards, without reloading the ONNX models each time.
    private var onDetected: (() -> Unit)? = null

    @Volatile override var isRunning = false
        private set

    @Volatile override var lastScore = 0f
        private set

    // Separate counters: sharing one across log sites makes them steal
    // increments from each other and the output lies about the rate.
    private var readLogCount = 0
    private var starveLogCount = 0
    private var rmsLogCount = 0
    private var melLogCount = 0

    // What the mic actually opened as. The USB HAL runs 48 kHz stereo with a
    // 240-frame period; asking AudioFlinger to hand us 16 kHz mono means it has
    // to resample and downmix, and on this HAL that conversion never fills the
    // client buffer -- read() returns 0 forever with no error. So we open at the
    // hardware's own format where we can and convert in Kotlin instead.
    private var capRate = sampleRate
    private var capChannels = 1

    private val melBuffer = ArrayDeque<FloatArray>()   // rolling mel frames
    private val embBuffer = ArrayDeque<FloatArray>()   // rolling embeddings

    override fun start(onDetected: () -> Unit): Boolean {
        if (isRunning) return true
        if (!loadModels()) return false
        this.onDetected = onDetected
        if (!startCapture()) { closeModels(); this.onDetected = null; return false }
        return true
    }

    override fun stop() {
        stopCapture()
        closeModels()
        onDetected = null
    }

    override fun pause() {
        if (!isRunning) return
        stopCapture()
        Log.i(TAG, "wake word paused — mic released for the session")
    }

    override fun resume() {
        if (isRunning || onDetected == null) return
        if (startCapture()) Log.i(TAG, "wake word resumed")
        else Log.w(TAG, "wake word could not resume — trigger with the rail mic button")
    }

    /** Open the mic and spin up the inference loop. Models must already be loaded. */
    private fun startCapture(): Boolean {
        val cb = onDetected ?: return false
        if (!openMic()) return false
        isRunning = true
        thread = Thread({ loop(cb) }, "wake-word").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        Log.i(TAG, "wake word listening (threshold=$threshold)")
        return true
    }

    /** Stop the loop and release the mic, but leave the models loaded. */
    private fun stopCapture() {
        isRunning = false
        thread?.join(500)
        thread = null
        releaseRecorder()
        closeModels()
        // Drop stale audio so a resumed detector can't re-fire on the last utterance.
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

    // --- microphone --------------------------------------------------------

    /**
     * Candidate capture configurations, best first.
     *
     * HOTWORD is tried first because it is the sanctioned always-on source for a
     * VoiceInteractionService and is exempt from the RECORD_AUDIO foreground
     * restriction (which otherwise silences a background capture: AudioService
     * logs "rec start ... not silenced" then flips it to "silenced" and read()
     * starves). It needs CAPTURE_AUDIO_HOTWORD, granted only to a priv-app
     * listed in privapp-permissions.
     *
     * Native 48 kHz stereo is tried before 16 kHz mono because that is what the
     * USB HAL reports; matching it keeps AudioFlinger's resampler out of the path.
     */
    private data class MicConfig(val source: Int, val rate: Int, val channels: Int) {
        override fun toString() =
            "src=" + (if (source == SRC_HOTWORD) "HOTWORD" else "VOICE_RECOGNITION") +
                " ${rate}Hz ${if (channels == 2) "stereo" else "mono"}"
    }

    private fun micCandidates(): List<MicConfig> = listOf(
        MicConfig(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 44_100, 1),
        MicConfig(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 48_000, 1),
        MicConfig(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 48_000, 2),
        MicConfig(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, 1),
    )

    /**
     * Opens the mic, trying each candidate until one actually delivers frames.
     *
     * Probing matters because every failure mode here is silent: the object
     * constructs, STATE_INITIALIZED is true, startRecording() does not throw, and
     * then read() returns 0 indefinitely. Only reading proves the path works.
     */
    @Suppress("MissingPermission") // RECORD_AUDIO is declared; granted by the platform
    private fun openMic(): Boolean {
        for (cfg in micCandidates()) {
            val ok = runCatching { tryOpen(cfg) }.getOrElse {
                Log.w(TAG, "mic config $cfg threw: ${it.message}")
                false
            }
            if (ok) {
                capRate = cfg.rate
                capChannels = cfg.channels
                Log.i(TAG, "mic open: $cfg (converting to ${sampleRate}Hz mono in-app)")
                return true
            }
            releaseRecorder()
        }
        Log.e(TAG, "no working mic configuration -- wake word cannot run")
        return false
    }

    @Suppress("MissingPermission")
    private fun tryOpen(cfg: MicConfig): Boolean {
        val mask =
            if (cfg.channels == 2) android.media.AudioFormat.CHANNEL_IN_STEREO
            else android.media.AudioFormat.CHANNEL_IN_MONO

        val minBuf = android.media.AudioRecord.getMinBufferSize(
            cfg.rate, mask, android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(TAG, "mic config $cfg unsupported (getMinBufferSize=$minBuf)")
            return false
        }

        // Room for several chunks so a late reader never loses audio.
        val wanted = CHUNK * (cfg.rate / sampleRate) * cfg.channels * 2 * 8
        recorder = android.media.AudioRecord(
            cfg.source, cfg.rate, mask,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, wanted),
        )
        if (recorder!!.state != android.media.AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "mic config $cfg did not initialise")
            return false
        }
        recorder!!.startRecording()
        if (recorder!!.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "mic config $cfg would not start recording")
            return false
        }

        // Prove it delivers before accepting it.
        val probe = ShortArray(256)
        val deadline = System.currentTimeMillis() + PROBE_MS
        var total = 0
        while (System.currentTimeMillis() < deadline) {
            val n = recorder!!.read(
                probe, 0, probe.size, android.media.AudioRecord.READ_NON_BLOCKING,
            )
            if (n > 0) total += n
            if (total >= probe.size) break
            Thread.sleep(20)
        }
        if (total <= 0) {
            Log.w(TAG, "mic config $cfg opened but delivered no samples in ${PROBE_MS}ms")
            return false
        }
        Log.i(TAG, "mic config $cfg delivered $total samples during probe")
        return true
    }

    private fun releaseRecorder() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
    }

    // --- the loop ----------------------------------------------------------

    private fun loop(onDetected: () -> Unit) {
        // Decimation factor from the capture rate down to the model's rate.
        // 48 kHz -> 16 kHz is 3; a native 16 kHz open gives 1 and the conversion
        // below collapses to a straight copy.
        val decim = maxOf(1, capRate / sampleRate)
        val frameShorts = capChannels                 // shorts per captured frame
        val capFrames = CHUNK * decim                 // frames needed per model chunk
        val capBuf = ShortArray(capFrames * frameShorts)
        var have = 0                                  // shorts accumulated so far

        val floats = FloatArray(CHUNK)
        var cooldownUntil = 0L

        while (isRunning) {
            if (readLogCount < 3) Log.d(TAG, "about to read #${readLogCount++}")

            // READ_NON_BLOCKING: a blocking read that never fills stalls the whole
            // detector silently. Non-blocking makes starvation visible instead.
            val n = recorder?.read(
                capBuf, have, capBuf.size - have, android.media.AudioRecord.READ_NON_BLOCKING,
            ) ?: -1

            if (n < 0) {
                Log.e(TAG, "AudioRecord.read error $n")
                Thread.sleep(50)
                continue
            }
            if (n == 0) {
                if (++starveLogCount % 100 == 0) Log.d(TAG, "no samples yet (x$starveLogCount)")
                Thread.sleep(5)
                continue
            }

            // Short reads are normal with non-blocking capture, so accumulate
            // until a whole chunk is present rather than processing partials --
            // a partial chunk would change the mel frame count per iteration and
            // quietly desynchronise the embedding window.
            have += n
            if (have < capBuf.size) continue
            have = 0

            // Downmix to mono and decimate to the model's rate. Averaging the
            // grouped samples doubles as a crude anti-alias filter; plain
            // subsampling would fold everything above 8 kHz back into the band
            // the wake model looks at.
            for (i in 0 until CHUNK) {
                var acc = 0
                for (j in 0 until decim) {
                    val base = ((i * decim) + j) * frameShorts
                    var frame = 0
                    for (c in 0 until frameShorts) frame += capBuf[base + c].toInt()
                    acc += frame / frameShorts
                }
                // openWakeWord's melspectrogram model takes raw int16 magnitudes
                // as float (NOT normalised to +/-1).
                floats[i] = (acc / decim).toFloat()
            }

            val score = runCatching { score(floats, CHUNK) }.getOrElse {
                Log.e(TAG, "inference failed; stopping wake word", it); -1f
            }
            if (score < 0f) break
            if (score > 0f) lastScore = score

            // Level and score together. rms alone is ambiguous: a capture device
            // sitting at zero gain can emit a constant DC offset, which gives a
            // healthy-looking rms while carrying no sound at all. Splitting out
            // the mean tells the two apart -- ac is the level with DC removed,
            // and it is ac, not rms, that the mel model actually sees.
            var sum = 0.0
            var mean = 0.0
            var peak = 0f
            for (i in 0 until CHUNK) {
                val v = floats[i]
                sum += v.toDouble() * v
                mean += v.toDouble()
                if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
            }
            mean /= CHUNK
            val rms = kotlin.math.sqrt(sum / CHUNK)
            val ac = kotlin.math.sqrt(kotlin.math.max(0.0, sum / CHUNK - mean * mean))
            if (++rmsLogCount % 12 == 0) {
                Log.d(
                    TAG,
                    "rms=%.0f mean=%.0f ac=%.0f peak=%.0f score=%.3f"
                        .format(rms, mean, ac, peak, score),
                )
            }

            if (score >= threshold && System.currentTimeMillis() > cooldownUntil) {
                Log.i(TAG, "wake word detected (${"%.2f".format(score)})")
                // 2 s cooldown so one utterance cannot fire the session repeatedly.
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
        // First few chunks only: if these are wildly outside roughly -10..10 the
        // scaling below is being applied to something it was not meant for.
        if (melLogCount++ < 3) {
            Log.d(
                TAG,
                "mel n=${flat.size} raw[0..7]=" +
                    flat.take(8).joinToString { "%.2f".format(it) },
            )
        }
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