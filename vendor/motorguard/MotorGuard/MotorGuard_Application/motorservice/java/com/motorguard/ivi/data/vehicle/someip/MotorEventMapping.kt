package com.motorguard.ivi.data.vehicle.someip

import android.util.Log
import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.RemainingLife
import com.motorguard.ivi.data.vehicle.api.Severity

/**
 * Turning one 16-byte event into the three fields the UI is written against (docs/09 §2.3).
 *
 * This is the only place a wire integer becomes a [Severity], and it is deliberately not in C++:
 * the interesting cases here are the wrong ones, and they are worth a unit test rather than a
 * device.
 */
internal object MotorEventMapping {

    /**
     * An unrecognised severity is **[Severity.CRITICAL]**, not OK.
     *
     * A level the unit gained and we were not told about is far more likely to mean "worse" than
     * "fine", and quietly painting it green is the one failure here that could matter. The log
     * line is what turns that into a bug report instead of a mystery.
     */
    fun severity(raw: Int): Severity = when (raw) {
        0 -> Severity.OK
        1 -> Severity.CAUTION
        2 -> Severity.CRITICAL
        else -> {
            Log.w(MotorLinkNative.TAG, "unknown severity $raw from the diagnostics unit; treating as CRITICAL")
            Severity.CRITICAL
        }
    }

    /**
     * Fault type by ordinal. An unrecognised type falls back to [MotorFaultType.NORMAL] for the
     * *label* only — the severity that came with it is kept untouched, because the colour is what
     * carries the safety meaning and the type only names it. A fault we cannot name is still a
     * fault.
     */
    fun faultType(raw: Int): MotorFaultType =
        MotorFaultType.entries.getOrElse(raw) {
            Log.w(MotorLinkNative.TAG, "unknown fault type $raw; label falls back to NORMAL")
            MotorFaultType.NORMAL
        }

    /**
     * Remaining life, or null when the unit says it has no estimate.
     *
     * `percent` is only carried when its own bit is set. It is never derived from `hours` against
     * an assumed design life: the bar it draws would be a claim nobody made.
     */
    fun remainingLife(flags: Int, hours: Float, percent: Float): RemainingLife? {
        if (flags and FLAG_RUL_VALID == 0) return null
        if (!hours.isFinite() || hours < 0f) return null
        val pct = percent.takeIf { flags and FLAG_RUL_PERCENT_VALID != 0 && it.isFinite() }
            ?.coerceIn(0f, 100f)
        return RemainingLife(hours = hours, percent = pct)
    }

    fun telemetry(faultTypeRaw: Int, severityRaw: Int, flags: Int, rulHours: Float, rulPercent: Float) =
        MotorTelemetry(
            faultType = faultType(faultTypeRaw),
            faultSeverity = severity(severityRaw),
            remainingLife = remainingLife(flags, rulHours, rulPercent),
        )

    private const val FLAG_RUL_VALID = 0x01
    private const val FLAG_RUL_PERCENT_VALID = 0x02
}
