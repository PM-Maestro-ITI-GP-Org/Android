package com.motorguard.ivi.data.vehicle.api

/**
 * Keeps the previous severity per signal so hysteresis can hold a value until it
 * clears the threshold by the margin. One resolver per (hotspot, sub-signal) pair;
 * key by "tire_fl.psi", "battery.cellTemp", etc. Thread safety is the caller's job —
 * both known data sources drive it from a single coroutine scope.
 */
class SeverityResolver(
    private val evaluator: SeverityEvaluator = SeverityEvaluator(),
) {
    private val last = mutableMapOf<String, Severity>()

    private fun resolve(
        key: String,
        value: Float,
        caution: Float,
        critical: Float,
        margin: Float,
        belowIsWorse: Boolean,
    ): Severity {
        val previous = last[key] ?: Severity.OK
        val next = SeverityEvaluator.withHysteresis(value, previous, caution, critical, margin, belowIsWorse)
        last[key] = next
        return next
    }

    fun tirePsi(hotspot: Hotspot, psi: Float): Severity = with(evaluator.thresholds) {
        resolve("tire.${hotspot.name}.psi", psi, tireCautionPsi, tireCriticalPsi, marginPsi, belowIsWorse = true)
    }

    fun batteryCharge(pct: Float): Severity = with(evaluator.thresholds) {
        resolve("battery.charge", pct, batteryChargeCaution, batteryChargeCritical, marginPercent, belowIsWorse = true)
    }

    fun batteryHealth(pct: Float): Severity = with(evaluator.thresholds) {
        resolve("battery.health", pct, batteryHealthCaution, batteryHealthCritical, marginPercent, belowIsWorse = true)
    }

    fun cellTemp(c: Float): Severity = with(evaluator.thresholds) {
        resolve("battery.cellTemp", c, cellTempCaution, cellTempCritical, marginTempC, belowIsWorse = false)
    }

    fun motorTemp(c: Float): Severity = with(evaluator.thresholds) {
        resolve("motor.temp", c, motorTempCaution, motorTempCritical, marginTempC, belowIsWorse = false)
    }

    fun motorLoad(pct: Float): Severity = with(evaluator.thresholds) {
        resolve("motor.load", pct, motorLoadCaution, Float.POSITIVE_INFINITY, marginPercent, belowIsWorse = false)
    }

    fun brakeWear(pct: Float): Severity = with(evaluator.thresholds) {
        resolve("brakes.wear", pct, brakeWearCaution, brakeWearCritical, marginPercent, belowIsWorse = false)
    }

    /** Stateless checks (no hysteresis benefit for booleans/aggregates). */
    fun brakeFluid(ok: Boolean): Severity = evaluator.brakeFluid(ok)
    fun doors(t: DoorsTelemetry): Severity = evaluator.doors(t)

    /** Worst-of severity across all sub-signals of one hotspot. */
    fun severityFor(hotspot: Hotspot, tirePsi: Float? = null, battery: BatteryTelemetry? = null,
                    motor: MotorTelemetry? = null, brakes: BrakeTelemetry? = null,
                    doorsState: DoorsTelemetry? = null): Severity = when (hotspot) {
        Hotspot.BATTERY -> evaluator.worstOf(
            batteryCharge(battery!!.chargePercent),
            batteryHealth(battery.healthPercent),
            cellTemp(battery.cellTempC),
        )
        Hotspot.MOTOR -> evaluator.worstOf(motorTemp(motor!!.tempC), motorLoad(motor.loadPercent))
        Hotspot.TIRE_FL, Hotspot.TIRE_FR, Hotspot.TIRE_RL, Hotspot.TIRE_RR ->
            tirePsi(hotspot, tirePsi!!)
        Hotspot.BRAKES -> brakes?.let {
            evaluator.worstOf(brakeWear(it.padWearPercent), brakeFluid(it.fluidOk))
        } ?: Severity.OK
        Hotspot.DOORS -> doorsState?.let { doors(it) } ?: Severity.OK
    }

    fun reset() = last.clear()
}
