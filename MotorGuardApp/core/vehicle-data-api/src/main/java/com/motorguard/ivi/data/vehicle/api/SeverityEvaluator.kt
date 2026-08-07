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
    // Battery
    val batteryChargeCaution: Float = 20f,
    val batteryChargeCritical: Float = 10f,
    val batteryHealthCaution: Float = 90f,
    val batteryHealthCritical: Float = 80f,
    // Temperatures (C)
    val cellTempCaution: Float = 45f,
    val cellTempCritical: Float = 60f,
    val motorTempCaution: Float = 90f,
    val motorTempCritical: Float = 120f,
    val motorLoadCaution: Float = 85f,
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

    fun batteryCharge(pct: Float): Severity = belowIsWorse(
        pct, thresholds.batteryChargeCaution, thresholds.batteryChargeCritical,
    )

    fun batteryHealth(pct: Float): Severity = belowIsWorse(
        pct, thresholds.batteryHealthCaution, thresholds.batteryHealthCritical,
    )

    fun cellTemp(c: Float): Severity = aboveIsWorse(
        c, thresholds.cellTempCaution, thresholds.cellTempCritical,
    )

    fun motorTemp(c: Float): Severity = aboveIsWorse(
        c, thresholds.motorTempCaution, thresholds.motorTempCritical,
    )

    fun motorLoad(pct: Float): Severity =
        if (pct >= thresholds.motorLoadCaution) Severity.CAUTION else Severity.OK

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
