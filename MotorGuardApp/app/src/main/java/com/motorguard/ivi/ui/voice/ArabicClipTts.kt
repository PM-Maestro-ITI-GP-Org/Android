// ArabicClipTts — owner D
// A text-to-speech engine for exactly one language, assembled from pre-recorded
// clips instead of a neural model.
//
// UNUSED as of the English-only-output change: PiperTtsService no longer
// calls this. Left in place (with its assets/voice_ar clips and manifest)
// rather than deleted, in case Arabic replies come back later -- see
// VoiceLanguage's doc comment for why output is English-only now.
//
// Why: no lightweight (Piper-class) Egyptian-accented voice exists publicly.
// The Egyptian-accented voices that do exist are heavy generative TTS models
// (Chatterbox/F5-TTS/T5-TTS) unsuited to real-time synthesis on this board.
// But the assistant's Arabic replies are built from a small, fixed, hand-
// written set of sentences (see dtc_seed.sql's _ar columns and Assistant.cpp's
// templates) -- never LLM-generated, by design. A closed sentence set can be
// rendered ONCE, offline, through one of those heavy models, and spliced back
// together live. This is that splicer.
//
// The manifest (assets/voice_ar/manifest.json) maps a normalized sentence to
// a clip id; the audio itself is assets/voice_ar/clips/<id>.wav. Most ids in
// the assistant's actual vocabulary do not have a clip yet -- see
// scripts/voice_ar_clips_todo.json for the full list still to render. A
// sentence with no clip is simply not spoken; PiperTtsService still shows the
// full text, so nothing is lost visually, only audibly.
package com.motorguard.ivi.ui.voice

import android.content.Context
import android.util.Log
import java.io.InputStream
import org.json.JSONObject

object ArabicClipTts {

    private const val TAG = "MotorGuardVoice"
    private const val ASSET_DIR = "voice_ar"
    private const val CLIPS_DIR = "$ASSET_DIR/clips"

    /** Sample rate every clip was rendered at; fixed because the whole set shares one voice. */
    const val SAMPLE_RATE = 24_000

    @Volatile private var manifest: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    @Synchronized
    fun ensureReady(context: Context): Boolean {
        if (loaded) return true
        loaded = runCatching {
            val json = context.assets.open("$ASSET_DIR/manifest.json")
                .bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val map = HashMap<String, String>(obj.length())
            obj.keys().forEach { key -> map[key] = obj.getString(key) }
            manifest = map
            Log.i(TAG, "arabic clip manifest loaded (${map.size} sentences)")
            true
        }.onFailure { Log.e(TAG, "arabic clip manifest failed to load", it) }
            .getOrDefault(false)
        return loaded
    }

    /**
     * @return PCM16 mono samples at [SAMPLE_RATE] for [sentence], or null if no
     *         clip covers it (not yet rendered, or the sentence has dynamic
     *         content -- a distance, a count -- that was never going to match
     *         a fixed clip in the first place).
     */
    fun clipFor(context: Context, sentence: String): ShortArray? {
        if (!loaded) return null
        val id = manifest[normalize(sentence)] ?: return null
        return runCatching {
            context.assets.open("$CLIPS_DIR/$id.wav").use { readWavPcm16(it) }
        }.onFailure { Log.w(TAG, "clip '$id' missing or unreadable", it) }
            .getOrNull()
    }

    /**
     * Matches PiperTtsService's SENTENCE split, which keeps terminal
     * punctuation attached -- so lookups must ignore it, since the manifest
     * was built from hand-typed source strings whose punctuation doesn't
     * always exactly match the punctuation the C++ core happens to append
     * during composition.
     */
    private fun normalize(s: String): String = s.trim().trimEnd('.', '!', '?', '؟', '،', ' ')

    /** Minimal RIFF/WAVE reader: finds the "data" chunk, returns its PCM16 samples. */
    private fun readWavPcm16(input: InputStream): ShortArray {
        val bytes = input.readBytes()
        require(bytes.size > 44) { "file too small to be a WAV" }
        var offset = 12  // past "RIFF" + size + "WAVE"
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = (bytes[offset + 4].toInt() and 0xFF) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            val body = offset + 8
            if (id == "data") { dataOffset = body; dataSize = size; break }
            offset = body + size + (size and 1)  // chunks are word-aligned
        }
        check(dataOffset >= 0) { "no data chunk found" }
        val n = minOf(dataSize, bytes.size - dataOffset) / 2
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val lo = bytes[dataOffset + i * 2].toInt() and 0xFF
            val hi = bytes[dataOffset + i * 2 + 1].toInt()
            pcm[i] = ((hi shl 8) or lo).toShort()
        }
        return pcm
    }
}
