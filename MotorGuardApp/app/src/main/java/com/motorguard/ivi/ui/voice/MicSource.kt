// MicSource — owner D
// The one piece of capture code that is known to work on this board.
package com.motorguard.ivi.ui.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * 16 kHz mono PCM from the microphone, whatever the hardware natively runs at.
 *
 * Extracted so the wake word and the recogniser share one capture path instead
 * of each inventing their own. Everything here is the product of a long
 * debugging session on AAOS / Raspberry Pi 5, and the non-obvious parts are:
 *
 *  - The USB HAL runs 48 kHz with a 240-frame period. Asking AudioFlinger for
 *    16 kHz mono makes it resample and downmix, and the converted client buffer
 *    then never fills: read() returns 0 forever with no error at any layer. So
 *    open at the hardware's rate and convert here.
 *
 *  - Every failure mode is silent. The object constructs, state is
 *    STATE_INITIALIZED, startRecording() does not throw, and only then does
 *    read() starve. So each configuration is PROVED by reading real frames
 *    before it is accepted.
 *
 *  - AudioSource.HOTWORD is exempt from the background capture restriction and
 *    does deliver frames, but on this board it delivers synthetic noise rather
 *    than microphone audio (two different mics gave statistically identical
 *    streams; the level scaled by exactly sqrt(2) between mono and stereo, which
 *    means independent noise per channel). It is deliberately not used.
 *    VOICE_RECOGNITION reaches the real capsule, and staying out of the
 *    background procstate -- see WakeWordService -- is what keeps it unsilenced.
 *
 * Not thread-safe. One owner at a time, which is also a hardware constraint:
 * a USB capture device has exactly one client.
 */
class MicSource(private val targetRate: Int = 16_000) {

    companion object {
        private const val TAG = "MotorGuardVoice"

        // Matches WakeWord.kt's MIC_GAIN -- keep the two in sync. 6.5x and 24x (both tried
        // earlier) were calibrated against card 2's weak onboard mic; neither applies now
        // that input capture is correctly redirected to card 3, the actual standalone
        // microphone (see usb_mic_route.patch/StreamUsb.cpp), which needs no software boost
        // -- with 6.5x still applied it clipped hard (peak pinned at the int16 ceiling).
        // 1x leaves the now-correct capture path alone.
        private const val MIC_GAIN = 1.0f

        /**
         * How long a candidate gets to deliver its first frames. Generous
         * because a real USB device coming up behind a closing stream is much
         * slower than a synthetic one.
         */
        private const val PROBE_MS = 3_000L
    }

    private data class Config(val rate: Int, val channels: Int) {
        override fun toString() = "${rate}Hz " + if (channels == 2) "stereo" else "mono"
    }

    private var recorder: AudioRecord? = null
    private var capRate = targetRate
    private var capChannels = 1
    private var decim = 1

    /** Interleaved capture buffer, sized in open(). */
    private var raw = ShortArray(0)
    private var have = 0

    val isOpen: Boolean get() = recorder != null

    /** Frames of 16 kHz mono delivered per successful read. */
    var chunkFrames = 1280
        private set

    @Suppress("MissingPermission") // RECORD_AUDIO is declared and granted at boot
    fun open(chunk: Int = 1280): Boolean {
        close()
        chunkFrames = chunk

        // Native rate first: it keeps AudioFlinger's resampler out of the path.
        val candidates = listOf(
            Config(48_000, 1),
            Config(48_000, 2),
            Config(targetRate, 1),
        )

        for (cfg in candidates) {
            val ok = runCatching { tryOpen(cfg) }
                .getOrElse { Log.w(TAG, "mic $cfg threw: ${it.message}"); false }
            if (ok) {
                capRate = cfg.rate
                capChannels = cfg.channels
                decim = maxOf(1, capRate / targetRate)
                raw = ShortArray(chunkFrames * decim * capChannels)
                have = 0
                Log.i(TAG, "mic open: $cfg -> ${targetRate}Hz mono in-app")
                return true
            }
            release()
        }
        Log.e(TAG, "no working mic configuration")
        return false
    }

    @Suppress("MissingPermission")
    private fun tryOpen(cfg: Config): Boolean {
        val mask =
            if (cfg.channels == 2) AudioFormat.CHANNEL_IN_STEREO
            else AudioFormat.CHANNEL_IN_MONO

        val minBuf = AudioRecord.getMinBufferSize(cfg.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "mic $cfg unsupported (getMinBufferSize=$minBuf)")
            return false
        }

        val wanted = chunkFrames * (cfg.rate / targetRate) * cfg.channels * 2 * 8
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            cfg.rate,
            mask,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, wanted),
        )
        if (recorder!!.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "mic $cfg did not initialise")
            return false
        }
        recorder!!.startRecording()
        if (recorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "mic $cfg would not start recording")
            return false
        }

        val probe = ShortArray(256)
        val deadline = System.currentTimeMillis() + PROBE_MS
        while (System.currentTimeMillis() < deadline) {
            val n = recorder!!.read(probe, 0, probe.size, AudioRecord.READ_NON_BLOCKING)
            if (n > 0) {
                Log.i(TAG, "mic $cfg delivered $n samples on probe")
                return true
            }
            if (n < 0) {
                Log.w(TAG, "mic $cfg read error $n")
                return false
            }
            Thread.sleep(20)
        }
        Log.w(TAG, "mic $cfg opened but delivered nothing in ${PROBE_MS}ms")
        return false
    }

    /**
     * Fills [dst] with exactly [chunkFrames] samples of 16 kHz mono, or returns
     * 0 if a whole chunk is not ready yet (caller should sleep briefly and retry)
     * or -1 on error.
     *
     * Partial chunks are accumulated rather than processed: a short chunk would
     * change the mel frame count per iteration and quietly desynchronise the
     * wake model's embedding window.
     */
    fun read(dst: ShortArray): Int {
        val rec = recorder ?: return -1

        val n = rec.read(raw, have, raw.size - have, AudioRecord.READ_NON_BLOCKING)
        if (n < 0) return -1
        if (n == 0) return 0
        have += n
        if (have < raw.size) return 0
        have = 0

        // Downmix, then decimate by averaging each group rather than dropping
        // samples: plain subsampling folds everything above 8 kHz back into the
        // band the models care about.
        for (i in 0 until chunkFrames) {
            var acc = 0
            for (j in 0 until decim) {
                val base = ((i * decim) + j) * capChannels
                var frame = 0
                for (c in 0 until capChannels) frame += raw[base + c].toInt()
                acc += frame / capChannels
            }
            dst[i] = ((acc / decim) * MIC_GAIN).coerceIn(
                Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat(),
            ).toInt().toShort()
        }
        return chunkFrames
    }

    fun close() = release()

    private fun release() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        have = 0
    }
}