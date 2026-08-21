package com.motorguard.ivi.data.vehicle.api

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
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

    // Heuristic: real SOME/IP captures are raw ADC counts (current ~2047, bus ~2633, vib ~1000),
    // fake synthesis is already SI (current ~11A, bus ~48V, vib ~0.2g). Scale only when raw.
    val probeCurrentMax = current[0].maxOrNull()?.let { abs(it) } ?: 0f
    val isRaw = probeCurrentMax > 50f
    var dcSum = 0.0
    var vibSquares = 0.0
    for (i in 0 until n) {
        val dcRaw = dcBusVolts[i]
        val dcV = if (isRaw) dcRaw * MotorCapture.VOLTS_PER_COUNT_BUS else dcRaw
        dcSum += dcV
        val vxRaw = vibration[0][i]
        val vyRaw = vibration[1][i]
        val vzRaw = vibration[2][i]
        val vx = if (isRaw) vxRaw / MotorCapture.IMU_COUNTS_PER_G else vxRaw
        val vy = if (isRaw) vyRaw / MotorCapture.IMU_COUNTS_PER_G else vyRaw
        val vz = if (isRaw) vzRaw / MotorCapture.IMU_COUNTS_PER_G else vzRaw
        vibSquares += (vx * vx + vy * vy + vz * vz).toDouble()
    }

    // Fake synthesis already emits SI (11A, 48V) — BlockAnalyzer expects raw 0..4095 counts and would
    // misread SI as raw. Detect once and fall back to direct SI arithmetic for the fake path.
    if (!isRaw) {
        var speedSum = 0.0
        var speedMax = 0f
        var powerSum = 0.0
        var powerPeak = 0f
        for (i in 0 until n) {
            val rpmI = rpm[i]
            speedSum += rpmI
            speedMax = max(speedMax, rpmI)
            val p = voltage[0][i] * current[0][i] + voltage[1][i] * current[1][i] + voltage[2][i] * current[2][i]
            powerSum += p
            powerPeak = max(powerPeak, p)
        }
        val windowFake = durationSec
        val avgPowerFake = (powerSum / n).toFloat()
        val rmsFake = run {
            var sum = 0.0
            for (p in 0 until 3) for (v in current[p]) sum += (v * v).toDouble()
            sqrt(sum / (3 * n)).toFloat()
        }
        return MotorCaptureSummary(
            capturedAtMs = capturedAtMs,
            windowSec = windowFake,
            averageSpeedRpm = (speedSum / n).toFloat(),
            maxSpeedRpm = speedMax,
            averagePowerW = avgPowerFake,
            peakPowerW = powerPeak,
            energyWh = avgPowerFake * windowFake / 3600f,
            rmsCurrentA = rmsFake,
            currentImbalancePercent = currentImbalancePercent(),
            vibrationRmsG = sqrt(vibSquares / n).toFloat(),
            speedTrackingErrorPercent = speedTrackingErrorPercent(emptyList()),
            averageDcBusVolts = (dcSum / n).toFloat(),
        )
    }

    val blocks = analyseBlocks()
    val validBlocks = blocks.filter { it.valid }

    // Averaged over the blocks that actually had something to measure — a block the angle
    // tracker never locked onto (motor stopped, or spinning up too slowly) contributes a
    // reported 0 that would otherwise drag the average down for a reason that has nothing to do
    // with how fast the motor is actually going the rest of the window.
    val averageSpeed = validBlocks.map { it.rpm }.average0()
    val maxSpeed = validBlocks.maxOfOrNull { it.rpm } ?: 0f
    val averagePower = blocks.map { it.watts }.average0()
    val peakPower = blocks.maxOfOrNull { it.watts } ?: 0f
    val rmsCurrent = blocks.map { it.currentRmsA }.average0()

    val window = durationSec

    return MotorCaptureSummary(
        capturedAtMs = capturedAtMs,
        windowSec = window,
        averageSpeedRpm = averageSpeed,
        maxSpeedRpm = maxSpeed,
        averagePowerW = averagePower,
        peakPowerW = peakPower,
        // Watt-hours over the captured window. Small by construction — a ten-second window of a
        // 450 W motor is about a milliwatt-hour — but it is the figure that scales to a duty cycle,
        // so it is reported rather than rounded away.
        energyWh = averagePower * window / 3600f,
        rmsCurrentA = rmsCurrent,
        currentImbalancePercent = currentImbalancePercent(),
        vibrationRmsG = sqrt(vibSquares / n).toFloat(),
        speedTrackingErrorPercent = speedTrackingErrorPercent(blocks),
        averageDcBusVolts = (dcSum / n).toFloat(),
    )
}

private fun List<Float>.average0(): Float = if (isEmpty()) 0f else (sum() / size)

// ---------------------------------------------------------------------------------------------
// Sensorless speed/power/current — a port of qt-cluster's MotorBlockAnalyzer (see
// MotorBlockAnalyzer.h in that repo), the same Clarke-transform angle tracker the instrument
// cluster uses to get shaft speed from the current waveform.
//
// This rig has no tach fitted, so `rpm[]` in a raw capture is 0 on every sample by hardware
// design (qt-cluster's own SpiReader.cpp documents this in the same words: "no tach is fitted,
// so the wire's rpm field is 0 on every row") — not something a bug fix here can recover, and
// not something worth reporting as a measurement. Speed is also aliased past Nyquist at anything
// coarser than the raw 20 kHz stream: the electrical fundamental reaches ~340 Hz, so it has to be
// computed over blocks of raw rows, never from a single sample or a naive mean/max of samples
// (which is what this file used to do, over a channel that is always zero).
//
// BLOCK_ROWS and the ADC/machine constants below are copied verbatim from MotorBlockAnalyzer.h,
// which measured them against a 36 s / 728,800-row bench capture (POLE_PAIRS, AMPS_PER_COUNT,
// the two voltage scales) — re-deriving them here would just be worse versions of numbers someone
// already got right. If the machine or its sensors change, update them there and mirror the
// change here; the two are meant to agree, not to independently guess.
// ---------------------------------------------------------------------------------------------

/** Rows per analysis block — the producer's own block_rows (config.json), and what the angle
 *  tracker was tuned against. 20 kHz / 200 rows = the 100 Hz block cadence MotorBlockAnalyzer.h
 *  is documented in terms of. */
private const val BLOCK_ROWS = 200

private const val POLE_PAIRS = 26
private const val VBUS_VOLTS = 48.0f
private const val PHASE_RAIL_COUNTS = 2071f
private const val VOLTS_PER_COUNT_PHASE = VBUS_VOLTS / PHASE_RAIL_COUNTS

/** Derived from the actual sensor circuit — motor_ai_node/config/feature_extraction.json's
 *  "adc" block: a shunt (0.0015 ohm) + current-sense-amplifier (gain 50) feeding a 12-bit ADC
 *  (4095 counts, 3.3 V reference), zero-current biased at 1.65 V (~2047 counts, [ADC_MIDSCALE]).
 *  amps = raw_volts / (shunt_r * csa_gain) = (raw/adc_max*vref) / (shunt_r*csa_gain), and since
 *  [ADC_MIDSCALE] already removes the bias in raw counts, only the slope is needed here:
 *      AMPS_PER_COUNT = vref / (adc_max * shunt_r * csa_gain) = 3.3 / (4095 * 0.0015 * 50)
 *  2047 counts (the removed bias) times this comes to 21.99 A — matching that same config's
 *  "max_current": 22.0 almost exactly, which is what makes this the trustworthy source over
 *  qt-cluster's own MotorBlockAnalyzer.h, whose 0.0085 is explicitly flagged there as a nameplate
 *  curve-fit ("454 W against 450 W... NOT an independent confirmation"), not a measurement of the
 *  sensor circuit itself. */
private const val AMPS_PER_COUNT = 3.3f / (4095f * 0.0015f * 50f)

private const val ADC_MIDSCALE = 2047f

/** Below this space-vector amplitude (counts) the machine is not turning usefully and the angle
 *  tracker has nothing to lock to; speed reads 0 rather than noise. Idle measures ~9. */
private const val I_RUNNING_COUNTS = 250f

/** Corner frequency of the pre-filter feeding the angle tracker — must sit above the highest
 *  electrical frequency (340 Hz here) and well below the switching ripple. */
private const val ANGLE_LP_HZ = 900f

private const val PI_F = 3.14159265f

private class BlockResult(val rpm: Float, val watts: Float, val currentRmsA: Float, val valid: Boolean)

/**
 * One instance per capture, reused across every block in it: the pre-filter and the previous
 * sample deliberately survive block boundaries (only the per-block accumulators reset), so a
 * rotation that happens right at a block's edge is not lost the way starting fresh every block
 * would lose it. A capture is a single, self-contained 10 s window with no continuity to any
 * other capture, so a fresh instance per [summarise] call — never reused across captures — is
 * the correct scope, unlike qt-cluster's own analyzer, which lives for the process and is reset
 * only on a ring lap.
 */
private class BlockAnalyzer {
    private var fAl = 0f
    private var fBe = 0f
    private var pAl = 0f
    private var pBe = 0f
    private var have = false

    fun process(current: Array<FloatArray>, voltage: Array<FloatArray>, from: Int, to: Int, rowRateHz: Float): BlockResult {
        var n = 0
        var nAng = 0
        var sumDth = 0.0
        var accP = 0.0
        var accI2 = 0.0

        val k = 1f - exp(-2f * PI_F * ANGLE_LP_HZ / rowRateHz)

        for (i in from until to) {
            val ia = current[0][i] - ADC_MIDSCALE
            val ib = current[1][i] - ADC_MIDSCALE
            val ic = current[2][i] - ADC_MIDSCALE

            // Force sum(i)=0 — the sensors carry a small common-mode term whose product with the
            // (much larger) voltage common-mode would otherwise be a pure artefact in the power sum.
            val cm = (ia + ib + ic) / 3f
            val a = ia - cm
            val b = ib - cm
            val c = ic - cm

            // Clarke. For a balanced set |(al,be)| is the phase amplitude and is steady through
            // the cycle, unlike any single phase sample.
            val ial = (2f / 3f) * (a - 0.5f * b - 0.5f * c)
            val ibe = (2f / 3f) * 0.8660254f * (b - c)

            val vA = voltage[0][i]
            val vB = voltage[1][i]
            val vC = voltage[2][i]
            val vAl = (2f / 3f) * (vA - 0.5f * vB - 0.5f * vC)
            val vBe = (2f / 3f) * 0.8660254f * (vB - vC)

            // Real power, averaged over the block — the phase voltages are raw PWM (0 or rail,
            // never the sinusoid they represent), so this is only meaningful averaged, never per row.
            accP += (1.5 * (vAl * ial + vBe * ibe)).toDouble()
            accI2 += (ial * ial + ibe * ibe).toDouble()

            // One-pole low pass ahead of the angle tracker, and ONLY ahead of it — power and RMS
            // want the unfiltered signal. Without this the tracker does not work at all: switching
            // ripple moves the space vector further between consecutive samples than the
            // fundamental does.
            fAl += k * (ial - fAl)
            fBe += k * (ibe - fBe)

            // Angle rate by cross product — the FFT-free speed measurement. No atan2 unwrap
            // needed and it cannot slip a cycle as long as the rotation is under half a turn per
            // sample (340 Hz at 20 kHz is 6 degrees — a 30x margin).
            val mag2 = fAl * fAl + fBe * fBe
            if (have && mag2 > I_RUNNING_COUNTS * I_RUNNING_COUNTS) {
                val cross = pAl * fBe - fAl * pBe
                val dot = pAl * fAl + pBe * fBe
                sumDth += atan2(cross, dot).toDouble()
                nAng++
            }
            pAl = fAl
            pBe = fBe
            have = true
            n++
        }

        // Require most of the block to have cleared the running-current gate, not just one row —
        // a block caught mid spin-up can have a handful of qualifying samples whose angle sum is
        // meaningless.
        val valid = n > 1 && nAng > (n * 4) / 5
        val electricalHz = if (valid) {
            (sumDth / (2.0 * Math.PI)).toFloat() * (rowRateHz / nAng.toFloat())
        } else {
            0f
        }
        val rpm = if (valid) abs(electricalHz) * 60f / POLE_PAIRS else 0f
        val watts = if (n > 0) (accP / n).toFloat() * VOLTS_PER_COUNT_PHASE * AMPS_PER_COUNT else 0f
        val currentRmsA = if (n > 0) sqrt(accI2 / n).toFloat() * AMPS_PER_COUNT * 0.70710678f else 0f

        return BlockResult(rpm, watts, currentRmsA, valid)
    }
}

private fun MotorCapture.analyseBlocks(): List<BlockResult> {
    val n = sampleCount
    if (n == 0) return emptyList()
    val analyzer = BlockAnalyzer()
    val results = ArrayList<BlockResult>((n + BLOCK_ROWS - 1) / BLOCK_ROWS)
    var start = 0
    while (start < n) {
        val end = minOf(start + BLOCK_ROWS, n)
        results += analyzer.process(current, voltage, start, end, MotorCapture.SAMPLE_RATE_HZ)
        start = end
    }
    return results
}

/** RMS of each phase current over the whole capture, midscale-subtracted for raw counts.
 *  Heuristic: real captures are raw ~2047, fake SI ~11A. Scale-invariant for imbalance either way. */
private fun MotorCapture.phaseRms(): FloatArray = FloatArray(3) { phase ->
    var squares = 0.0
    val channel = current[phase]
    val probe = channel.maxOrNull()?.let { abs(it) } ?: 0f
    val isRawPh = probe > 50f
    for (i in channel.indices) {
        val v = if (isRawPh) (channel[i] - ADC_MIDSCALE).toDouble() else channel[i].toDouble()
        squares += v * v
    }
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
 * How far the [BlockAnalyzer]'s measured speed strays from what the command asked for, as a
 * percentage of full scale — computed per [BLOCK_ROWS] block, the same granularity the speed
 * estimate itself has, rather than per sample: comparing a per-sample command against a
 * per-block speed would mostly be comparing against block-to-block noise.
 *
 * The command is a voltage, not an rpm, so the two are compared through the ratio the capture
 * itself establishes across its valid blocks — no hard-coded volts-per-rpm constant, which would
 * be a property of the controller rather than of this app.
 */
private fun MotorCapture.speedTrackingErrorPercent(blocks: List<BlockResult>): Float {
    val n = sampleCount
    if (n == 0 || blocks.isEmpty()) return 0f

    val blockCmdMeans = FloatArray(blocks.size)
    var start = 0
    var bi = 0
    while (start < n) {
        val end = minOf(start + BLOCK_ROWS, n)
        var sum = 0.0
        for (i in start until end) sum += speedVoltCmd[i]
        blockCmdMeans[bi] = (sum / (end - start)).toFloat()
        start = end
        bi++
    }

    val validIdx = blocks.indices.filter { blocks[it].valid }
    if (validIdx.isEmpty()) return 0f

    val cmdMean = validIdx.map { blockCmdMeans[it] }.average0()
    val rpmMean = validIdx.map { blocks[it].rpm }.average0()
    if (cmdMean < 1e-3f || rpmMean < 1e-3f) return 0f

    val rpmPerVolt = rpmMean / cmdMean
    var errorSum = 0.0
    var scale = 0f
    for (idx in validIdx) {
        val expected = blockCmdMeans[idx] * rpmPerVolt
        errorSum += abs(blocks[idx].rpm - expected).toDouble()
        scale = max(scale, expected)
    }
    if (scale < 1e-3f) return 0f
    return ((errorSum / validIdx.size).toFloat() / scale) * 100f
}
