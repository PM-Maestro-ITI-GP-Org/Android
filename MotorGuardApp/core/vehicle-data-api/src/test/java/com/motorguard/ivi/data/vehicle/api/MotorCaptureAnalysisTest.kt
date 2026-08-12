package com.motorguard.ivi.data.vehicle.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Every number the motor card shows now comes from here — the vehicle publishes only a fault
 * classification — so these are checked against waveforms whose answers are known by hand rather
 * than against whatever the generator happens to produce.
 */
class MotorCaptureAnalysisTest {

    private val rate = MotorCapture.SAMPLE_RATE_HZ.toInt()

    /** A balanced three-phase capture at a steady speed: 48 V bus, 750 rpm, 10 A peak per phase. */
    private fun steadyCapture(
        seconds: Float = 1f,
        peakAmps: Float = 10f,
        phaseGain: FloatArray = floatArrayOf(1f, 1f, 1f),
        rpmValue: Float = 750f,
        electricalHz: Float = 162.5f,
    ): MotorCapture {
        val n = (rate * seconds).toInt()
        val current = Array(3) { FloatArray(n) }
        val voltage = Array(3) { FloatArray(n) }
        val vibration = Array(3) { FloatArray(n) }
        val dc = FloatArray(n) { 48f }
        val rpm = FloatArray(n) { rpmValue }
        val cmd = FloatArray(n) { 4.2f }
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            val theta = 2f * PI.toFloat() * electricalHz * t
            for (p in 0 until 3) {
                val offset = 2f * PI.toFloat() * p / 3f
                current[p][i] = peakAmps * phaseGain[p] * sin(theta + offset)
                voltage[p][i] = 24f * sin(theta + offset)
            }
        }
        return MotorCapture(1L, cmd, current, voltage, dc, vibration, rpm)
    }

    @Test
    fun `speed is averaged and its peak kept`() {
        val n = rate
        val rpm = FloatArray(n) { if (it < n / 2) 500f else 700f }
        val capture = steadyCapture().copy(rpm = rpm)
        val s = capture.summarise()
        assertEquals(600f, s.averageSpeedRpm, 1f)
        assertEquals(700f, s.maxSpeedRpm, 0.01f)
    }

    /**
     * Power is summed per phase as v*i, which is what makes it valid for a trapezoidal BLDC drive
     * as well as a sinusoidal one. For three balanced sinusoids in phase, the mean of the sum is
     * 3 * Vpk * Ipk / 2.
     */
    @Test
    fun `average power matches the analytic value for balanced phases`() {
        val s = steadyCapture(peakAmps = 10f).summarise()
        val expected = 3f * 24f * 10f / 2f
        assertEquals(expected, s.averagePowerW, expected * 0.02f)
        assertTrue("peak must exceed the mean", s.peakPowerW > s.averagePowerW)
    }

    @Test
    fun `energy is the average power over the captured window`() {
        val s = steadyCapture(seconds = 2f).summarise()
        assertEquals(s.averagePowerW * 2f / 3600f, s.energyWh, 1e-4f)
        assertEquals(2f, s.windowSec, 1e-3f)
    }

    /** RMS of a sine is its peak over root two. */
    @Test
    fun `rms current matches the analytic value`() {
        val s = steadyCapture(peakAmps = 10f).summarise()
        assertEquals(10f / 1.41421f, s.rmsCurrentA, 0.05f)
    }

    @Test
    fun `balanced phases report no imbalance`() {
        assertEquals(0f, steadyCapture().summarise().currentImbalancePercent, 0.5f)
    }

    /**
     * One weak phase is the electrical-fault signature the card highlights. Deviation is measured
     * from the mean of the three, so a phase at 0.85 of the others sits 10% below a mean of 0.95.
     */
    @Test
    fun `one weak phase shows up as imbalance`() {
        val s = steadyCapture(phaseGain = floatArrayOf(1f, 0.85f, 1f)).summarise()
        assertEquals(10.5f, s.currentImbalancePercent, 1.5f)
    }

    /**
     * A stopped motor has three currents of zero, which are perfectly balanced. Reporting an
     * unbalance there would be an artefact of dividing by nearly nothing, and it would paint the
     * card's electrical-fault evidence row red every time the vehicle was parked.
     */
    @Test
    fun `a stopped motor reports no imbalance rather than a divide by zero`() {
        val s = steadyCapture(peakAmps = 0f, rpmValue = 0f).summarise()
        assertEquals(0f, s.currentImbalancePercent, 1e-4f)
        assertTrue(s.currentImbalancePercent.isFinite())
    }

    @Test
    fun `speed that follows the command reports almost no tracking error`() {
        assertEquals(0f, steadyCapture().summarise().speedTrackingErrorPercent, 1f)
    }

    @Test
    fun `speed that wanders from the command reports tracking error`() {
        val n = rate
        val rpm = FloatArray(n) { 750f + 150f * sin(2f * PI.toFloat() * 2f * it / rate) }
        val s = steadyCapture().copy(rpm = rpm).summarise()
        assertTrue("expected a visible error, got ${s.speedTrackingErrorPercent}", s.speedTrackingErrorPercent > 5f)
    }

    @Test
    fun `vibration is the rms of the three axes together`() {
        val n = rate
        val vib = Array(3) { axis -> FloatArray(n) { if (axis == 0) 0.3f else 0.4f } }
        val s = steadyCapture().copy(vibration = vib).summarise()
        // sqrt(0.09 + 0.16 + 0.16)
        assertEquals(0.640f, s.vibrationRmsG, 0.005f)
    }

    @Test
    fun `dc bus is averaged`() {
        assertEquals(48f, steadyCapture().summarise().averageDcBusVolts, 0.01f)
    }

    /** An empty capture must summarise to zeroes rather than NaN: a division by a zero sample
     *  count would reach the card as "NaN W". */
    @Test
    fun `an empty capture summarises to zeroes`() {
        val empty = MotorCapture(
            capturedAtMs = 7L,
            speedVoltCmd = FloatArray(0),
            current = Array(3) { FloatArray(0) },
            voltage = Array(3) { FloatArray(0) },
            dcBusVolts = FloatArray(0),
            vibration = Array(3) { FloatArray(0) },
            rpm = FloatArray(0),
        )
        val s = empty.summarise()
        assertEquals(7L, s.capturedAtMs)
        listOf(
            s.averageSpeedRpm, s.maxSpeedRpm, s.averagePowerW, s.peakPowerW, s.energyWh,
            s.rmsCurrentA, s.currentImbalancePercent, s.vibrationRmsG,
            s.speedTrackingErrorPercent, s.averageDcBusVolts,
        ).forEach { assertTrue("every field must be finite", it.isFinite()) }
    }
}
