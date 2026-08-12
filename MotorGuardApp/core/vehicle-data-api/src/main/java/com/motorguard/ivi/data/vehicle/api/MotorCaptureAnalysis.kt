package com.motorguard.ivi.data.vehicle.api

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Reduces a capture to the numbers the card shows.
 *
 * This is where every quantitative claim about the motor now comes from. Nothing is published
 * continuously except the fault classification, so "what speed was it doing" and "how much power
 * did it draw" are answered by analysing the window the user asked for — which is also the honest
 * scope for those answers, since they describe that window and nothing else.
 *
 * Pure, and in the domain module rather than the UI, so it can be tested against known waveforms
 * and so a real transport that computes these on the diagnostics unit can be checked against it.
 */
fun MotorCapture.summarise(): MotorCaptureSummary {
    val n = sampleCount
    if (n == 0) {
        return MotorCaptureSummary(
            capturedAtMs = capturedAtMs,
            windowSec = 0f,
            averageSpeedRpm = 0f,
            maxSpeedRpm = 0f,
            averagePowerW = 0f,
            peakPowerW = 0f,
            energyWh = 0f,
            rmsCurrentA = 0f,
            currentImbalancePercent = 0f,
            vibrationRmsG = 0f,
            speedTrackingErrorPercent = 0f,
            averageDcBusVolts = 0f,
        )
    }

    var speedSum = 0.0
    var speedMax = 0f
    var powerSum = 0.0
    var powerPeak = 0f
    var dcSum = 0.0
    var vibSquares = 0.0

    for (i in 0 until n) {
        val rpmI = rpm[i]
        speedSum += rpmI
        speedMax = max(speedMax, rpmI)

        // Instantaneous three-phase power. Summing v*i per phase is what makes this valid for a
        // trapezoidal BLDC drive as well as a sinusoidal one — no assumption of a power factor,
        // no assumption that the waveform is a sine.
        val p = voltage[0][i] * current[0][i] +
            voltage[1][i] * current[1][i] +
            voltage[2][i] * current[2][i]
        powerSum += p
        powerPeak = max(powerPeak, p)

        dcSum += dcBusVolts[i]

        val vx = vibration[0][i]
        val vy = vibration[1][i]
        val vz = vibration[2][i]
        vibSquares += (vx * vx + vy * vy + vz * vz).toDouble()
    }

    val window = durationSec
    val averagePower = (powerSum / n).toFloat()

    return MotorCaptureSummary(
        capturedAtMs = capturedAtMs,
        windowSec = window,
        averageSpeedRpm = (speedSum / n).toFloat(),
        maxSpeedRpm = speedMax,
        averagePowerW = averagePower,
        peakPowerW = powerPeak,
        // Watt-hours over the captured window. Small by construction — a ten-second window of a
        // 450 W motor is about a milliwatt-hour — but it is the figure that scales to a duty cycle,
        // so it is reported rather than rounded away.
        energyWh = averagePower * window / 3600f,
        rmsCurrentA = phaseRms().let { sqrt((it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) / 3f) },
        currentImbalancePercent = currentImbalancePercent(),
        vibrationRmsG = sqrt(vibSquares / n).toFloat(),
        speedTrackingErrorPercent = speedTrackingErrorPercent(),
        averageDcBusVolts = (dcSum / n).toFloat(),
    )
}

/** RMS of each phase current over the whole capture. */
private fun MotorCapture.phaseRms(): FloatArray = FloatArray(3) { phase ->
    var squares = 0.0
    val channel = current[phase]
    for (i in channel.indices) squares += (channel[i] * channel[i]).toDouble()
    if (channel.isEmpty()) 0f else sqrt(squares / channel.size).toFloat()
}

/**
 * How far apart the three phase currents are, as a percentage of their mean RMS — the standard
 * definition of current unbalance, and the signature of an electrical fault.
 *
 * Maximum deviation from the mean rather than max-minus-min: one open or shorted winding moves one
 * phase away from the other two, and the deviation of that phase is the number an engineer expects
 * to see. Zero when the motor is stopped, because three currents of zero are perfectly balanced
 * and reporting an unbalance there would be an artefact of dividing by nearly nothing.
 */
private fun MotorCapture.currentImbalancePercent(): Float {
    val rms = phaseRms()
    val mean = (rms[0] + rms[1] + rms[2]) / 3f
    if (mean < 1e-3f) return 0f
    val worst = maxOf(abs(rms[0] - mean), abs(rms[1] - mean), abs(rms[2] - mean))
    return worst / mean * 100f
}

/**
 * How far measured speed strays from what the command asked for, as a percentage of full scale.
 *
 * The command is a voltage, not an rpm, so the two are compared through the ratio the capture
 * itself establishes: the average command maps to the average speed, and the error is what is left
 * over. That avoids hard-coding a volts-per-rpm constant here, which would be a property of the
 * controller rather than of this app, and would silently go wrong when the controller changed.
 */
private fun MotorCapture.speedTrackingErrorPercent(): Float {
    val n = sampleCount
    if (n == 0) return 0f
    var cmdSum = 0.0
    var rpmSum = 0.0
    for (i in 0 until n) {
        cmdSum += speedVoltCmd[i]
        rpmSum += rpm[i]
    }
    val cmdMean = (cmdSum / n).toFloat()
    val rpmMean = (rpmSum / n).toFloat()
    if (cmdMean < 1e-3f || rpmMean < 1e-3f) return 0f

    val rpmPerVolt = rpmMean / cmdMean
    var errorSum = 0.0
    var scale = 0f
    for (i in 0 until n) {
        val expected = speedVoltCmd[i] * rpmPerVolt
        errorSum += abs(rpm[i] - expected).toDouble()
        scale = max(scale, expected)
    }
    if (scale < 1e-3f) return 0f
    return ((errorSum / n).toFloat() / scale) * 100f
}
