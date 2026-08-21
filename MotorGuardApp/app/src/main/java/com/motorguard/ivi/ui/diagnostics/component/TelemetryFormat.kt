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

    /** Watts, not kilowatts: this is a 450 W machine, and "0.4 kW" throws away the resolution
     *  that makes two runs comparable. */
    fun watts(value: Float): String = String.format(Locale.US, "%.0f W", value)

    fun amps(value: Float): String = String.format(Locale.US, "%.2f A", value)

    fun volts(value: Float): String = value.roundToInt().toString()

    fun rpmWithUnit(value: Float): String =
        String.format(Locale.US, "%,d rpm", value.roundToInt())

    fun gForce(value: Float): String = String.format(Locale.US, "%.2f g", value)

    /** Capture window length, in the coarsest form that stays exact. */
    fun seconds(value: Float): String =
        if (value >= 1f) String.format(Locale.US, "%.0f s", value)
        else String.format(Locale.US, "%.0f ms", value * 1000f)

    /** Hours are what the driver acts on, so they never become "1.2 k" — a service interval read
     *  as a rounded magnitude is one someone can be a week wrong about.
     *  Auto-scales 2922h -> "4 months" etc: <48h as hours, <30d as days, <12mo as months, else years. */
    fun hours(value: Float): String {
        val abs = kotlin.math.abs(value)
        return when {
            abs < 48f -> String.format(Locale.US, "%,d h", value.roundToInt())
            abs < 720f -> {
                val days = value / 24f
                val dInt = days.roundToInt()
                if (dInt == 1) "1 day" else String.format(Locale.US, "%d days", dInt)
            }
            abs < 8760f -> {
                val months = value / 730.5f
                // 2922h = 4.0 months -> "4 months" not "4.0 months"
                val mInt = months.roundToInt()
                val isNearInt = kotlin.math.abs(months - mInt) < 0.05f
                if (isNearInt) {
                    if (mInt == 1) "1 month" else String.format(Locale.US, "%d months", mInt)
                } else {
                    String.format(Locale.US, "%.1f months", months)
                }
            }
            else -> {
                val years = value / 8760f
                val yInt = years.roundToInt()
                val isNearInt = kotlin.math.abs(years - yInt) < 0.05f
                if (isNearInt) {
                    if (yInt == 1) "1 year" else String.format(Locale.US, "%d years", yInt)
                } else {
                    String.format(Locale.US, "%.1f years", years)
                }
            }
        }
    }

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
