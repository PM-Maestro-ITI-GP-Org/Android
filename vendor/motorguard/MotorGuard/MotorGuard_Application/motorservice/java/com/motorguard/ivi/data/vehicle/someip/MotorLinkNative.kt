package com.motorguard.ivi.data.vehicle.someip

import android.util.Log

/**
 * The whole JNI surface, and nothing else.
 *
 * Six primitives out for an event, one float array in for a capture. No types from
 * `vehicle-data-api` appear here on purpose: the native library knows about bytes on a wire, and
 * every decision about what those bytes mean — which severity, how stale is stale, what the user
 * is told when it fails — is made in Kotlin where it can be tested without a device on the desk.
 */
internal object MotorLinkNative {

    /** True when libmotorguardsomeip.so is present and loaded. */
    val available: Boolean = runCatching { System.loadLibrary("motorguardsomeip") }
        .onFailure {
            // Not fatal and not unexpected: a Gradle/emulator build has no such library, and the
            // caller falls back to whatever source it was going to use anyway. Saying so once is
            // worth more than a crash.
            Log.w(TAG, "libmotorguardsomeip not loaded (${it.message}); SOME/IP link unavailable")
        }
        .isSuccess

    /** Returns a session handle, or 0 if the sockets could not be opened at all. */
    external fun nativeOpen(
        listener: Listener,
        serviceId: Int,
        instanceId: Int,
        majorVersion: Int,
        eventgroupId: Int,
        eventId: Int,
        captureMethodId: Int,
        clientId: Int,
        sdMulticast: String,
        sdPort: Int,
        localEventPort: Int,
        staticHost: String,
        staticUdpPort: Int,
        staticTcpPort: Int,
        subscribeTtlSec: Int,
        captureTimeoutMs: Int,
        /** 0 (NETWORK_UNSPECIFIED) if the Ethernet Network could not be resolved at open time. */
        androidNetworkHandle: Long,
    ): Long

    external fun nativeClose(handle: Long)

    external fun nativeReconnect(handle: Long)

    /**
     * Blocks until the capture completes, fails or times out. Fills [header] with
     * `[status, channelCount, sampleCount, sampleRateMilliHz, capturedAtMs, headerLayout]`
     * and returns the same status.
     */
    external fun nativeRequestCapture(handle: Long, requestedDurationSec: Float, header: LongArray): Int

    /** Copies the samples of the last capture; [dest] must be exactly channelCount * sampleCount. */
    external fun nativeCopySamples(handle: Long, dest: FloatArray): Boolean

    /** Frees the native copy. Always call it, including after a failure. */
    external fun nativeReleaseCapture(handle: Long)

    /** Abandons an in-flight capture from another thread. */
    external fun nativeCancelCapture(handle: Long)

    /**
     * Called from the link's own native thread, never the main thread. Implementations must not
     * block: the same thread is what receives the next event.
     */
    internal interface Listener {
        /**
         * @param remoteTimestampMs the sender's own clock, which is monotonic-since-boot on that
         *   device and therefore **not** comparable with [System.currentTimeMillis]. See
         *   [MotorFreshness] for what is used instead and why.
         */
        fun onEvent(faultType: Int, severity: Int, flags: Int, remoteTimestampMs: Long, rulHours: Float, rulPercent: Float)

        /** [LinkState] as an ordinal: 0 down, 1 offered, 2 subscribed. */
        fun onLink(state: Int)
    }

    /** Mirrors `motorguard::someip::CaptureError`; anything else non-zero is the peer's own status. */
    object CaptureError {
        const val NO_ENDPOINT = -1
        const val CONNECT = -2
        const val IO = -3
        const val TIMEOUT = -4
        const val MALFORMED = -5
        const val CANCELLED = -6
        const val BAD_SAMPLES = -7
    }

    /** Mirrors docs/10 §5.3 `status`. */
    object PeerStatus {
        const val OK = 0
        const val BUSY = 1
        const val ACQUISITION_FAILED = 2
        const val NOT_READY = 3
        const val UNSUPPORTED_DURATION = 4
    }

    const val TAG = "MotorGuardLink"
}
