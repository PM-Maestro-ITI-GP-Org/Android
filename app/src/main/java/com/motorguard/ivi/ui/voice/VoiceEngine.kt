package com.motorguard.ivi.ui.voice

import android.util.Log

/**
 * The reasoning core, reached over JNI. Everything that decides *meaning* —
 * which fault the driver is asking about, how serious it is, what to say — lives
 * in C++ under `src/main/cpp/assistant-core/` and is shared with the Linux build.
 *
 * Kotlin deliberately owns only the platform edges (mic, STT, TTS, UI). Nothing
 * in this class interprets a fault itself; it forwards and returns text.
 *
 * Thread-safety: the native side serialises calls behind a mutex, so this is safe
 * to call from the session thread and the wake-word thread.
 */
object VoiceEngine {

    private const val TAG = "MotorGuardVoice"

    @Volatile private var initialised = false

    init {
        runCatching { System.loadLibrary("motorguardvoice") }
            .onFailure { Log.e(TAG, "native library failed to load", it) }
    }

    /** Builds the engine and loads the embedded fault database. Idempotent. */
    @Synchronized
    fun ensureReady(): Boolean {
        if (initialised) return true
        initialised = runCatching { nativeInit() }.getOrElse {
            Log.e(TAG, "nativeInit failed", it); false
        }
        if (initialised) Log.i(TAG, "core ready — ${faultCount()} fault definitions")
        return initialised
    }

    /** Fault definitions available. 0 means the core never came up. */
    fun faultCount(): Int = if (ensureReadyQuiet()) runCatching { nativeFaultCount() }.getOrDefault(0) else 0

    /**
     * Ask the core about a recognised utterance.
     * @return reply to show and speak, or null if the core produced nothing.
     */
    fun handle(utterance: String): String? {
        if (!ensureReady() || utterance.isBlank()) return null
        val reply = runCatching { nativeHandle(utterance) }.getOrElse {
            Log.e(TAG, "nativeHandle failed", it); null
        }
        return reply?.takeIf { it.isNotBlank() }
    }

    /**
     * Push a fault in from the vehicle layer. Wire this to `CarDataRepository`
     * once VHAL/CAN is connected; until then Diagnostics or a demo button can
     * call it.
     *
     * @return the proactive announcement if the fault was urgent enough for the
     *         assistant to speak up unprompted, otherwise null.
     */
    fun pushFault(
        code: String,
        predicted: Boolean = false,
        sensorKey: String = "",
        sensorValue: Double = 0.0,
    ): String? {
        if (!ensureReady() || code.isBlank()) return null
        return runCatching { nativePushFault(code, predicted, sensorKey, sensorValue) }
            .getOrElse { Log.e(TAG, "nativePushFault failed", it); null }
            ?.takeIf { it.isNotBlank() }
    }

    fun clearFaults() {
        if (ensureReadyQuiet()) runCatching { nativeClearFaults() }
    }

    private fun ensureReadyQuiet(): Boolean = initialised || ensureReady()

    // --- native ------------------------------------------------------------
    private external fun nativeInit(): Boolean
    private external fun nativeFaultCount(): Int
    private external fun nativeHandle(utterance: String): String
    private external fun nativePushFault(
        code: String, predicted: Boolean, sensorKey: String, sensorValue: Double,
    ): String
    private external fun nativeClearFaults()
}
