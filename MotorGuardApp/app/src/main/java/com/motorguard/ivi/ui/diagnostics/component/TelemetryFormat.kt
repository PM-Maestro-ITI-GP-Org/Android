package com.motorguard.ivi.ui.diagnostics.component

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The single place a telemetry number becomes a string.
 *
 * Precision is chosen per field rather than uniformly. Percentages and temperatures round to whole
 * units because the source drifts them by well under one unit per tick, and a last digit flickering
 * under a display-sized numeral reads as a fault in the instrument rather than movement in the
 * vehicle. PSI keeps one decimal because its thresholds sit on a 1-psi scale, so 0.2 psi of drift
 * is genuinely meaningful there. Counts group thousands so RPM stays scannable at a glance.
 *
 * [Locale.US] is deliberate for grouping: these are instrument readouts, and a separator that
 * changes with the head unit's locale would change how a number parses at a glance mid-drive.
 */
internal object TelemetryFormat {

    fun percent(value: Float): String = "${value.roundToInt()}%"

    /** Degree sign only — the unit letter belongs in the field's caption, not the numeral. */
    fun tempC(value: Float): String = "${value.roundToInt()}°"

    fun psi(value: Float): String = String.format(Locale.US, "%.1f", value)

    fun rpm(value: Int): String = String.format(Locale.US, "%,d", value)

    fun count(value: Int): String = String.format(Locale.US, "%,d", value)

    /** Answers the cell's own label ("Charging"), so the value stays short enough for a quarter of
     *  a narrow panel. "Not charging" needed three lines there — see [MetricCell]'s note on why a
     *  wrapping value is a layout bug and not just a tight fit. */
    fun charging(on: Boolean): String = if (on) "Yes" else "No"

    /**
     * Bare numerals, unit in the caption — the same rule as [tempC], and for a harder reason here.
     * These three share one row by weight in a narrow panel, so "15.9 kW" ellipsised to "15.9 k…"
     * while "341 V" beside it fitted. A numeral with no unit attached cannot outgrow its cell.
     */
    fun powerKw(value: Float): String = String.format(Locale.US, "%.1f", value)

    fun volts(value: Float): String = value.roundToInt().toString()

    /** Full-width capture rows, not weighted cells, so these keep their units. */
    fun powerKwWithUnit(value: Float): String = String.format(Locale.US, "%.1f kW", value)

    fun gForce(value: Float): String = String.format(Locale.US, "%.2f g", value)

    /** Hours are what the driver acts on, so they never become "1.2 k" — a service interval read
     *  as a rounded magnitude is one someone can be a week wrong about. */
    fun hours(value: Float): String = String.format(Locale.US, "%,d h", value.roundToInt())

    fun percentPrecise(value: Float): String = String.format(Locale.US, "%.1f%%", value)

    /**
     * How old a capture is, in the coarsest unit that is still honest. A capture minutes old is
     * fine to show; the point of the label is that nobody mistakes it for live.
     */
    fun ageOf(timestampMs: Long, nowMs: Long): String {
        val seconds = ((nowMs - timestampMs) / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            else -> "${seconds / 3600}h ago"
        }
    }

    fun fluid(ok: Boolean): String = if (ok) "OK" else "Low"

    fun doorOpen(open: Boolean): String = if (open) "Open" else "Closed"

    fun doorLock(locked: Boolean): String = if (locked) "Locked" else "Unlocked"

    /**
     * Elapsed milliseconds as a coarse, glanceable age: "12s ago", "3m ago", "1h ago".
     *
     * Coarse on purpose — the number exists to answer "how far should I trust this?", and a
     * second-accurate reading of a value that is already known to be stale invites false precision.
     * Negative input (a clock that moved backwards) clamps to zero rather than printing nonsense.
     */
    fun age(elapsedMs: Long): String {
        val seconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
        return when {
            seconds < 60L -> "${seconds}s ago"
            seconds < 3_600L -> "${seconds / 60L}m ago"
            else -> "${seconds / 3_600L}h ago"
        }
    }
}
