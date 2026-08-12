package com.motorguard.ivi.data.vehicle.api

/**
 * All alert thresholds in one transport-agnostic place — never duplicated in the
 * fake data generator or the UI (README-phase1-ui-brief §3). Tuning these changes
 * every green/amber/red in the surface at once.
 */
data class SeverityThresholds(
    // Tire PSI (band around the recommended pressure)
    val recommendedPsi: Float = 34f,
    val tireCautionPsi: Float = 28f,
    val tireCriticalPsi: Float = 24f,
    // Tire carcass temperature. Was specified in the phase-1 brief but never given a rule here,
    // which left the UI showing tire temp ungraded — the one field on the detail card with a
    // documented threshold and no way to apply it.
    val tireTempCaution: Float = 70f,
    val tireTempCritical: Float = 85f,
    // Battery
    val batteryChargeCaution: Float = 20f,
    val batteryChargeCritical: Float = 10f,
    val batteryHealthCaution: Float = 90f,
    val batteryHealthCritical: Float = 80f,
    // Temperatures (C)
    val cellTempCaution: Float = 45f,
    val cellTempCritical: Float = 60f,
    // No motor thresholds: this vehicle has no load or temperature sensor, and the motor's
    // severity arrives already classified by the diagnostics unit (see SeverityResolver.motor).
    // Brakes
    val brakeWearCaution: Float = 70f,
    val brakeWearCritical: Float = 90f,
    // Hysteresis margins: a value crossing INTO a worse state must pass the
    // threshold; recovering to a better state needs to clear it by this margin.
    // Prevents dot colors flickering on a borderline sample.
    val marginPsi: Float = 1f,
    val marginPercent: Float = 2f,
    val marginTempC: Float = 2f,
)

/**
 * Maps raw telemetry to [Severity] with hysteresis. Hysteresis needs the previous
 * severity per hotspot, which lives in [SeverityResolver] — the evaluator is a
 * holder for the stateless raw mappings only.
 *
 * Direction: for PSI/charge/health LOW is bad ("below is worse"); for temperature,
 * load and wear HIGH is bad ("above is worse").
 */
class SeverityEvaluator(val thresholds: SeverityThresholds = SeverityThresholds()) {

    fun curb(psi: Float): Severity = belowIsWorse(
        psi, thresholds.tireCautionPsi, thresholds.tireCriticalPsi,
    )

    fun tireTemp(c: Float): Severity = aboveIsWorse(
        c, thresholds.tireTempCaution, thresholds.tireTempCritical,
    )

    fun batteryCharge(pct: Float): Severity = belowIsWorse(
        pct, thresholds.batteryChargeCaution, thresholds.batteryChargeCritical,
    )

    fun batteryHealth(pct: Float): Severity = belowIsWorse(
        pct, thresholds.batteryHealthCaution, thresholds.batteryHealthCritical,
    )

    fun cellTemp(c: Float): Severity = aboveIsWorse(
        c, thresholds.cellTempCaution, thresholds.cellTempCritical,
    )

    fun brakeWear(pct: Float): Severity = aboveIsWorse(
        pct, thresholds.brakeWearCaution, thresholds.brakeWearCritical,
    )

    fun brakeFluid(ok: Boolean): Severity =
        if (ok) Severity.OK else Severity.CRITICAL

    fun doors(t: DoorsTelemetry): Severity = when {
        t.anyOpen -> Severity.CRITICAL
        t.anyUnlocked -> Severity.CAUTION
        else -> Severity.OK
    }

    /** Worst-of aggregate used for the Tires/Motor/Battery hotspot overall severity. */
    fun worstOf(vararg severities: Severity): Severity =
        severities.maxBy { it.ordinal }

    private fun belowIsWorse(value: Float, caution: Float, critical: Float): Severity = when {
        value <= critical -> Severity.CRITICAL
        value <= caution -> Severity.CAUTION
        else -> Severity.OK
    }

    private fun aboveIsWorse(value: Float, caution: Float, critical: Float): Severity = when {
        value >= critical -> Severity.CRITICAL
        value >= caution -> Severity.CAUTION
        else -> Severity.OK
    }

    companion object {
        /**
         * Apply hysteresis: the new severity must be *strictly worse* than [previous]
         * to escalate; improving requires the raw value to clear the threshold by
         * [margin]. Used by every signal's per-hotspot resolver.
         */
        fun withHysteresis(
            value: Float,
            previous: Severity,
            caution: Float,
            critical: Float,
            margin: Float,
            belowIsWorse: Boolean,
        ): Severity {
            val escalate: Severity = if (belowIsWorse) when {
                value <= critical -> Severity.CRITICAL
                value <= caution -> Severity.CAUTION
                else -> Severity.OK
            } else when {
                value >= critical -> Severity.CRITICAL
                value >= caution -> Severity.CAUTION
                else -> Severity.OK
            }
            if (escalate.ordinal >= previous.ordinal) return escalate

            // Trying to improve — require clearing the relevant threshold by the margin.
            val improved: Severity = if (belowIsWorse) when {
                value <= critical + margin -> Severity.CRITICAL
                value <= caution + margin -> Severity.CAUTION
                else -> Severity.OK
            } else when {
                value >= critical - margin -> Severity.CRITICAL
                value >= caution - margin -> Severity.CAUTION
                else -> Severity.OK
            }
            return improved
        }
    }
}
