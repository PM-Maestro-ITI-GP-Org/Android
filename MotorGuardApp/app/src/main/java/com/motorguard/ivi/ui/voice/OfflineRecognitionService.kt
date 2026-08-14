// OfflineRecognitionService — owner D
// Offline speech-to-text, so SpeechRecognizer.isRecognitionAvailable() stops
// returning false on a Google-free AAOS build.
package com.motorguard.ivi.ui.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * A RecognitionService backed by whisper.cpp, running fully on-device.
 *
 * Why this exists: stock AOSP/AAOS ships no RecognitionService at all -- that is
 * a Google Play Services component. Without one,
 * VoiceOverlaySession.startListening() bails at isRecognitionAvailable() and the
 * assistant opens a session that can never hear anything.
 *
 * Why Whisper and not Vosk: Vosk's small model transcribed "P0217" as "pee zero
 * to one southern". The surrounding English was perfect -- only the alphanumeric
 * code failed, because a general language model has no reason to expect
 * letter-digit sequences. Whisper's initial_prompt conditioning (see
 * WhisperStt.PROMPT) biases decoding toward real DTC codes, which is the actual
 * reason for the switch.
 *
 * The cost is latency. Whisper has no streaming mode: it consumes a complete
 * utterance in one pass, so there are no partial results and nothing appears
 * until the user stops talking. Two things here fight that: SILENCE_MS is short,
 * and the audio is trimmed to the speech before inference.
 *
 * Why it does its own capture: MicSource opens at the hardware's native 48 kHz
 * and converts in-app, which is the only path proven to deliver frames on this
 * board's USB HAL. See MicSource for the full reasoning.
 *
 * Register it after install:
 *   settings --user 10 put secure voice_recognition_service \
 *     com.motorguard.ivi/.ui.voice.OfflineRecognitionService
 */
class OfflineRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "MotorGuardVoice"

        /** 80 ms at 16 kHz, matching the wake word so both share MicSource. */
        private const val CHUNK = 1280

        /**
         * Stop after this much continuous silence following speech. This is dead
         * time the user feels directly, before inference has even started, so it
         * is kept tight. Much below ~500 ms and a mid-sentence pause ends the
         * utterance early.
         */
        private const val SILENCE_MS = 600L

        /** Hard cap: also bounds how long Whisper will then have to chew on. */
        private const val MAX_SESSION_MS = 12_000L

        /** Give up if the user never speaks at all. */
        private const val NO_SPEECH_MS = 6_000L

        /**
         * Above this RMS a chunk counts as speech. Tuned on this hardware:
         * ambient sits near 100-250, speech runs 1000-3400.
         */
        private const val SPEECH_RMS = 400.0

        /** Floor reported for a silent chunk, rather than log10(0) = -infinity. */
        private const val SILENCE_DB = -60f

        private const val MAX_SAMPLES = (MAX_SESSION_MS * 16).toInt()  // 16 kHz

        /**
         * Padding kept either side of the detected speech. A hard cut on the VAD
         * boundary clips quiet leading consonants and trailing fricatives, which
         * costs accuracy for no real speed gain.
         */
        private const val PAD_PRE = 4_000    // 0.25 s at 16 kHz
        private const val PAD_POST = 5_600   // 0.35 s
    }

    private var worker: Thread? = null
    @Volatile private var cancelled = false

    override fun onCreate() {
        super.onCreate()
        // Loading costs seconds and must not happen on the first utterance.
        // WhisperStt holds the context process-wide, so this is a no-op after
        // the first service instance.
        Thread({ WhisperStt.ensureReady(filesDir) }, "whisper-load").start()
    }

    override fun onStartListening(intent: Intent?, listener: Callback) {
        if (worker != null) {
            safe { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        cancelled = false
        worker = Thread({ run(listener) }, "whisper-stt").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun run(listener: Callback) {
        // The wake word owns the mic until now. One USB capture device, one
        // client -- so it has to let go before this can open.
        WakeWordService.pause()

        val mic = MicSource()
        try {
            if (!WhisperStt.ensureReady(filesDir)) {
                Log.e(TAG, "recognition requested but no model is loaded")
                safe { listener.error(SpeechRecognizer.ERROR_SERVER) }
                return
            }
            if (!mic.open(CHUNK)) {
                safe { listener.error(SpeechRecognizer.ERROR_AUDIO) }
                return
            }
            safe { listener.readyForSpeech(Bundle()) }

            val buf = ShortArray(CHUNK)
            val utterance = ShortArray(MAX_SAMPLES)
            var used = 0

            val started = System.currentTimeMillis()
            var lastVoice = 0L
            var heardSpeech = false

            // Sample offsets of the speech itself, so the silence around it can
            // be trimmed before inference. Whisper's cost scales with what it is
            // handed, and a capture is often more silence than speech.
            var voiceStart = 0
            var voiceEnd = 0

            while (!cancelled) {
                val now = System.currentTimeMillis()
                if (now - started > MAX_SESSION_MS) break
                if (heardSpeech && now - lastVoice > SILENCE_MS) break
                if (!heardSpeech && now - started > NO_SPEECH_MS) break

                val n = mic.read(buf)
                if (n < 0) {
                    safe { listener.error(SpeechRecognizer.ERROR_AUDIO) }
                    return
                }
                if (n == 0) {
                    Thread.sleep(5)
                    continue
                }

                val chunkStart = used
                if (used + n <= utterance.size) {
                    System.arraycopy(buf, 0, utterance, used, n)
                    used += n
                }

                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = kotlin.math.sqrt(sum / n)

                // Reported in dB, because that is what onRmsChanged is specified to carry
                // and what every consumer scales as. This sent the raw PCM amplitude
                // instead — 0..32767 — so the overlay's ((rms + 2) / 12) mapping produced
                // 64 for an ordinary 769 and clamped to 1.0 on any sound at all. The
                // waveform was not dead, it was pinned wide open, which looks the same:
                // it never moved when you spoke.
                //
                // dBFS: 0 is full scale, about -45 a quiet cabin, -15 loud speech. The
                // speech gate below keeps using the linear value, since its threshold is
                // calibrated in those units.
                val rmsDb = if (rms > 1.0) {
                    (20.0 * kotlin.math.log10(rms / Short.MAX_VALUE.toDouble())).toFloat()
                } else {
                    SILENCE_DB
                }
                safe { listener.rmsChanged(rmsDb) }

                if (rms > SPEECH_RMS) {
                    if (!heardSpeech) {
                        heardSpeech = true
                        voiceStart = chunkStart
                        safe { listener.beginningOfSpeech() }
                    }
                    lastVoice = now
                    voiceEnd = used
                }
            }

            safe { listener.endOfSpeech() }
            // Release the mic before inference: Whisper pins several cores for a
            // second or more, and holding a USB capture stream through that
            // risks overruns and blocks the wake word from resuming.
            mic.close()

            if (cancelled) return
            if (!heardSpeech) {
                safe { listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                return
            }

            val from = maxOf(0, voiceStart - PAD_PRE)
            val to = minOf(used, voiceEnd + PAD_POST)
            val trimmed = utterance.copyOfRange(from, maxOf(from, to))
            Log.d(TAG, "trimmed $used -> ${trimmed.size} samples")

            val text = WhisperStt.transcribe(trimmed, trimmed.size)
            if (text.isBlank()) {
                safe { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
            } else {
                Log.i(TAG, "heard: $text")
                safe { listener.results(bundleOf(text)) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recognition failed", t)
            safe { listener.error(SpeechRecognizer.ERROR_CLIENT) }
        } finally {
            mic.close()
            worker = null
            // Hand the mic back so the wake word can listen again.
            WakeWordService.resume { VoiceOverlayService.requestSession() }
        }
    }

    private fun bundleOf(text: String) = Bundle().apply {
        putStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION,
            arrayListOf(text),
        )
    }

    /** Callback methods are cross-process and throw RemoteException. */
    private inline fun safe(block: () -> Unit) {
        runCatching { block() }.onFailure { Log.w(TAG, "callback failed: ${it.message}") }
    }

    override fun onStopListening(listener: Callback) {
        // Stop capturing but still transcribe what was collected.
        cancelled = false
        worker?.interrupt()
    }

    override fun onCancel(listener: Callback) {
        cancelled = true
        worker?.interrupt()
    }

    override fun onDestroy() {
        cancelled = true
        worker?.interrupt()
        // WhisperStt is process-wide and deliberately NOT released here: the
        // platform destroys this service after every utterance, and reloading
        // the model each time would add seconds to each turn.
        super.onDestroy()
    }
}