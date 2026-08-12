package com.motorguard.ivi.data.vehicle.someip

import android.util.Log
import com.motorguard.ivi.data.vehicle.api.CaptureState
import com.motorguard.ivi.data.vehicle.api.MotorCapture
import com.motorguard.ivi.data.vehicle.api.MotorCaptureSource
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * The on-demand raw capture: one request, one TCP connection, roughly 9.6 MB back.
 *
 * Never throws — every failure becomes [CaptureState.Failed] with a sentence, because that string
 * is rendered to the driver verbatim (docs/09 §5.5). A stack trace on the panel is not a failure
 * report, it is an apology in the wrong language.
 */
internal class SomeIpMotorCaptureSource(
    private val link: SomeIpMotorLink,
    private val config: MotorLinkConfig,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MotorCaptureSource {

    override suspend fun requestCapture(): CaptureState {
        if (!link.opened) return CaptureState.Failed(NO_LINK)

        return withContext(io) {
            val handle = link.nativeHandle
            // The native call blocks its thread, so cancellation has to reach across: closing the
            // panel cancels this coroutine, the handler shuts the socket down, and the blocked
            // read returns CANCELLED instead of holding an IO thread for the full 20 s ceiling.
            val cancelling = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) MotorLinkNative.nativeCancelCapture(handle)
            }
            try {
                decode(handle)
            } finally {
                cancelling?.dispose()
                MotorLinkNative.nativeReleaseCapture(handle)
            }
        }
    }

    private fun decode(handle: Long): CaptureState {
        val header = LongArray(HEADER_FIELDS)
        val status = MotorLinkNative.nativeRequestCapture(handle, config.requestedCaptureSec, header)
        if (status != 0) return CaptureState.Failed(message(status))

        val channels = header[1].toInt()
        val samples = header[2].toInt()
        val sampleRateHz = header[3] / 1000f
        val layout = header[5].toInt()

        // The native side already refused a ragged payload, a non-finite sample and a channel
        // count that is not twelve. Re-checking the arithmetic here costs nothing and means the
        // slicing below cannot be the thing that throws.
        if (channels != CHANNELS || samples <= 0) return CaptureState.Failed(EMPTY)

        // docs/09 §5.2: SAMPLE_RATE_HZ is compiled into the plot's time axis. A unit running at a
        // different rate produces a capture that looks perfectly fine and is plotted against the
        // wrong seconds, which nothing downstream can detect — so it is refused here, loudly,
        // rather than shipped as a silent mismatch.
        val expected = MotorCapture.SAMPLE_RATE_HZ
        if (kotlin.math.abs(sampleRateHz - expected) > expected * RATE_TOLERANCE) {
            Log.e(MotorLinkNative.TAG, "capture at $sampleRateHz Hz, app assumes $expected Hz")
            return CaptureState.Failed(
                "The diagnostics unit captured at ${sampleRateHz.toInt()} Hz, " +
                    "but this screen plots ${expected.toInt()} Hz."
            )
        }

        val flat = FloatArray(channels * samples)
        if (!MotorLinkNative.nativeCopySamples(handle, flat)) return CaptureState.Failed(EMPTY)

        Log.i(MotorLinkNative.TAG, "capture decoded: $samples samples/channel, ${layout}-byte header")

        // Channel-major on the wire (docs/10 §5.3), one FloatArray per channel here. The order is
        // fixed by that table and this is the only place it is written down in Kotlin.
        fun channel(index: Int) = flat.copyOfRange(index * samples, (index + 1) * samples)

        return CaptureState.Ready(
            MotorCapture(
                // The unit's own timestamp is monotonic-since-boot on that device, so it would
                // render as 1970 on the card. Local receive time is the same fallback the live
                // signal makes, for the same unsolved clock alignment — see [MotorFreshness].
                capturedAtMs = System.currentTimeMillis(),
                speedVoltCmd = channel(0),
                current = arrayOf(channel(1), channel(2), channel(3)),
                voltage = arrayOf(channel(4), channel(5), channel(6)),
                dcBusVolts = channel(7),
                vibration = arrayOf(channel(8), channel(9), channel(10)),
                rpm = channel(11),
            )
        )
    }

    /**
     * One sentence per way this can go wrong, each naming something different to go and look at.
     * "Unreachable", "timed out", "malformed" and "refused by the unit" are the four the spec
     * asks to be distinguishable, and they are.
     */
    private fun message(status: Int): String = when (status) {
        MotorLinkNative.CaptureError.NO_ENDPOINT ->
            "The diagnostics unit has not been found on the network."
        MotorLinkNative.CaptureError.CONNECT ->
            "The diagnostics unit did not accept the connection."
        MotorLinkNative.CaptureError.IO ->
            "The connection to the diagnostics unit dropped during the transfer."
        MotorLinkNative.CaptureError.TIMEOUT ->
            "The diagnostics unit did not finish the capture in time."
        MotorLinkNative.CaptureError.MALFORMED ->
            "The diagnostics unit sent a reply this app could not read."
        MotorLinkNative.CaptureError.CANCELLED -> CANCELLED
        MotorLinkNative.CaptureError.BAD_SAMPLES ->
            "The capture arrived incomplete and was discarded."
        MotorLinkNative.PeerStatus.BUSY ->
            "The diagnostics unit is already taking a capture."
        MotorLinkNative.PeerStatus.ACQUISITION_FAILED ->
            "The diagnostics unit could not read the motor's signals."
        MotorLinkNative.PeerStatus.NOT_READY ->
            "The diagnostics unit is not ready to capture yet."
        MotorLinkNative.PeerStatus.UNSUPPORTED_DURATION ->
            "The diagnostics unit cannot capture a window this long."
        else -> "The diagnostics unit refused the capture (status $status)."
    }

    private companion object {
        const val HEADER_FIELDS = 6
        const val CHANNELS = 12
        const val RATE_TOLERANCE = 0.005f  // half a percent

        const val NO_LINK = "The diagnostics link is not running on this build."
        const val EMPTY = "The capture returned no samples."
        const val CANCELLED = "The capture was cancelled."
    }
}
