package com.motorguard.ivi.data.vehicle.fake

import com.motorguard.ivi.data.vehicle.api.CaptureState
import com.motorguard.ivi.data.vehicle.api.MotorCapture
import com.motorguard.ivi.data.vehicle.api.MotorCaptureSource
import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesises a capture that looks like a three-phase drive rather than like noise, so the plot can
 * be judged before there is hardware to plot.
 *
 * The signals are related the way the real ones are: the phase currents are 120 degrees apart, the
 * voltages lead them, the DC bus sags as current rises, and vibration carries a component at the
 * shaft frequency. A generator that filled each channel independently would produce curves that
 * look plausible alone and obviously wrong on top of each other, which is exactly the view the
 * popup opens on.
 *
 * [faultType] shapes the defect: an electrical fault unbalances one phase, a mechanical fault adds
 * a vibration harmonic, a sensor fault makes the measured speed disagree with the command.
 */
class FakeMotorCaptureSource(
    private val clock: () -> Long = System::currentTimeMillis,
    private val computeContext: CoroutineContext,
    private val faultTypeProvider: () -> MotorFaultType = { MotorFaultType.NORMAL },
    private val failureProvider: () -> String? = { null },
) : MotorCaptureSource {

    override suspend fun requestCapture(): CaptureState {
        // Acquisition is not instant on real hardware, and a popup whose pending state never
        // appears in development is a pending state nobody has ever looked at.
        delay(ACQUISITION_MILLIS)
        failureProvider()?.let { return CaptureState.Failed(it) }
        // Off the main thread: 200,000 samples across twelve channels is tens of milliseconds of
        // arithmetic, and the popup is animating while this runs.
        val capture = withContext(computeContext) { synthesise(faultTypeProvider()) }
        return CaptureState.Ready(capture)
    }

    private fun synthesise(fault: MotorFaultType): MotorCapture {
        val n = (DURATION_SEC * MotorCapture.SAMPLE_RATE_HZ).toInt()
        val dt = 1f / MotorCapture.SAMPLE_RATE_HZ

        val speedCmd = FloatArray(n)
        val current = Array(3) { FloatArray(n) }
        val voltage = Array(3) { FloatArray(n) }
        val dcBus = FloatArray(n)
        val vibration = Array(3) { FloatArray(n) }
        val rpm = FloatArray(n)

        // One phase carries less current than the other two when the fault is electrical. This is
        // the imbalance the card reports as "current balance", drawn rather than asserted.
        val phaseGain = when (fault) {
            MotorFaultType.ELECTRICAL -> floatArrayOf(1f, 0.86f, 1.04f)
            else -> floatArrayOf(1f, 1f, 1f)
        }

        for (i in 0 until n) {
            val t = i * dt
            // A ramp, a cruise and a lift-off, so the whole-capture view has a shape worth showing
            // before the user ever touches the scrubber.
            val envelope = when {
                t < RAMP_SEC -> t / RAMP_SEC
                t < DURATION_SEC - RAMP_SEC -> 1f
                else -> ((DURATION_SEC - t) / RAMP_SEC).coerceAtLeast(0.25f)
            }
            speedCmd[i] = SPEED_CMD_FULL_SCALE_V * envelope + noise(0.008f)

            // Electrical frequency follows shaft speed through the pole pairs, which is why the
            // current view shows far more cycles than the speed view does.
            val shaftHz = MAX_RPM * envelope / 60f
            val electricalHz = shaftHz * POLE_PAIRS
            val theta = 2f * PI.toFloat() * electricalHz * t
            val amplitude = PHASE_CURRENT_PEAK_A * envelope

            for (p in 0 until 3) {
                val offset = 2f * PI.toFloat() * p / 3f
                current[p][i] = amplitude * phaseGain[p] * sin(theta + offset) + noise(0.25f)
                // Voltage leads current by roughly a quarter cycle in an inductive load, and can
                // never exceed the bus that produced it.
                voltage[p][i] = (BUS_NOMINAL_V / 2f) * envelope * sin(theta + offset + PI.toFloat() / 5f) +
                    noise(0.4f)
            }

            // The bus sags under load and carries the switching ripple, which is what makes it
            // worth its own view at all.
            dcBus[i] = BUS_NOMINAL_V - 4.5f * envelope + 0.35f * sin(theta * 6f) + noise(0.12f)

            val shaftTheta = 2f * PI.toFloat() * shaftHz * t
            val mechanical = if (fault == MotorFaultType.MECHANICAL) 0.9f else 0.05f
            vibration[0][i] = 0.22f * envelope * sin(shaftTheta) +
                mechanical * envelope * sin(shaftTheta * 2.5f) + noise(0.03f)
            vibration[1][i] = 0.18f * envelope * sin(shaftTheta + 1.1f) + noise(0.03f)
            vibration[2][i] = 0.12f * envelope * sin(shaftTheta + 2.4f) +
                mechanical * 0.4f * envelope * sin(shaftTheta * 3.5f) + noise(0.03f)

            // A sensor fault is the measured speed disagreeing with the commanded one; everything
            // else in the capture stays healthy, which is what makes it hard to spot without this
            // view and easy with it.
            val trackingError = if (fault == MotorFaultType.SENSOR) {
                1f + 0.18f * sin(2f * PI.toFloat() * 0.7f * t)
            } else {
                1f
            }
            rpm[i] = shaftHz * 60f * trackingError + noise(2.5f)
        }

        return MotorCapture(
            capturedAtMs = clock(),
            speedVoltCmd = speedCmd,
            current = current,
            voltage = voltage,
            dcBusVolts = dcBus,
            vibration = vibration,
            rpm = rpm,
        )
    }

    private fun noise(scale: Float) = (Random.nextFloat() - 0.5f) * 2f * scale

    private companion object {
        /** Long enough for the envelope to have a beginning, middle and end. */
        const val DURATION_SEC = 10f
        const val RAMP_SEC = 2.5f

        // The real machine: a 48 V, 450 W BLDC turning at up to about 750 rpm.
        const val BUS_NOMINAL_V = 48f
        const val MAX_RPM = 750f

        /**
         * Pole PAIRS, not poles. Reported as "11 or 13" and not yet confirmed; 13 is used here.
         * It only sets how many electrical cycles appear per revolution, so a wrong value makes
         * the current view denser or sparser and changes nothing else — but it is the number to
         * correct first when the real waveform is compared against this one.
         */
        const val POLE_PAIRS = 13f

        /** 450 W at 48 V is about 9.4 A of bus current; a phase peak somewhat above that. */
        const val PHASE_CURRENT_PEAK_A = 11f

        const val SPEED_CMD_FULL_SCALE_V = 4.2f

        /** Stands in for the round trip and the acquisition window on the diagnostics unit. */
        const val ACQUISITION_MILLIS = 1_400L
    }
}
