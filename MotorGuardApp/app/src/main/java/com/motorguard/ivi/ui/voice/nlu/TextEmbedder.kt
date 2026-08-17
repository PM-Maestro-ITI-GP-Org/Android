package com.motorguard.ivi.ui.voice.nlu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.LongBuffer

/**
 * Turns a sentence into a 384-dimension vector, so two ways of saying the same thing land in
 * the same place.
 *
 * all-MiniLM-L6-v2, run on the ONNX Runtime the wake word already pulls in — no second
 * inference stack, no extra native library, and 22M parameters against the wake word's own
 * models. It encodes an utterance in single-digit milliseconds on this board, which is what
 * makes it usable *before* the assistant decides anything, rather than as another stage the
 * driver waits through.
 *
 * The model emits per-token vectors, not a sentence vector. Mean-pooling them under the
 * attention mask and normalising is the recipe the model was trained with — skipping the mask
 * averages in the padding, and skipping the normalisation makes cosine similarity depend on
 * sentence length.
 */
class TextEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: WordPiece,
) {

    /** @return a unit-length vector, or null if the model could not be run. */
    fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        val ids = tokenizer.encode(text)
        val n = ids.size

        return runCatching {
            val idBuf = LongBuffer.allocate(n)
            val maskBuf = LongBuffer.allocate(n)
            val typeBuf = LongBuffer.allocate(n)
            for (i in 0 until n) {
                idBuf.put(ids[i].toLong())
                maskBuf.put(1L)   // no padding: one sentence per call, so every token is real
                typeBuf.put(0L)   // single segment
            }
            idBuf.rewind(); maskBuf.rewind(); typeBuf.rewind()

            val shape = longArrayOf(1, n.toLong())
            val inputs = mutableMapOf<String, OnnxTensor>()
            inputs["input_ids"] = OnnxTensor.createTensor(env, idBuf, shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, maskBuf, shape)
            // Present in this export; older ones omit it, so only bind what the model declares.
            if (session.inputNames.contains("token_type_ids")) {
                inputs["token_type_ids"] = OnnxTensor.createTensor(env, typeBuf, shape)
            }

            val vector = session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val tokens = result[0].value as Array<Array<FloatArray>>
                meanPool(tokens[0])
            }
            inputs.values.forEach { it.close() }
            normalise(vector)
            vector
        }.getOrElse {
            Log.w(TAG, "embedding failed", it)
            null
        }
    }

    private fun meanPool(tokens: Array<FloatArray>): FloatArray {
        val dim = tokens.firstOrNull()?.size ?: return FloatArray(0)
        val out = FloatArray(dim)
        for (token in tokens) for (i in 0 until dim) out[i] += token[i]
        for (i in 0 until dim) out[i] /= tokens.size.toFloat()
        return out
    }

    /** In place: cosine similarity is then just a dot product. */
    private fun normalise(v: FloatArray) {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = kotlin.math.sqrt(sum)
        if (norm > 1e-6f) for (i in v.indices) v[i] /= norm
    }

    fun close() = runCatching { session.close() }.let { }

    companion object {
        private const val TAG = "MotorGuardVoice"

        const val MODEL_FILE = "minilm.onnx"
        const val VOCAB_FILE = "minilm_vocab.txt"

        /**
         * @return null when either file is absent — the assistant then falls back to the paths
         *         it had before, rather than the launcher failing because a model was not
         *         pushed to the image.
         */
        fun load(model: File, vocab: File): TextEmbedder? = runCatching {
            val tokenizer = WordPiece.load(vocab) ?: return null
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // Four A76s, and nothing else is competing at the moment an utterance ends.
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(model.absolutePath, options)
            Log.i(TAG, "embedder ready (${model.name})")
            TextEmbedder(env, session, tokenizer)
        }.getOrElse {
            Log.w(TAG, "embedder unavailable", it)
            null
        }
    }
}
