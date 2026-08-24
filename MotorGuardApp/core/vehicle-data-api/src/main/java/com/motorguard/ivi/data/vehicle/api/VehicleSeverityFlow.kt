package com.motorguard.ivi.data.vehicle.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The per-hotspot severity map the UI renders (dot color, card banner, alert candidates), and
 * the health ring's score — both derived from [Hotspot.MOTOR] alone.
 *
 * Battery/brakes/doors/tires have no real sensor behind them on this vehicle
 * (motorservice/README.md, "Only the motor comes off this link"); grading their simulated
 * drift produced faults — a hotspot dot going amber, an alert-list row, a beep, the screen
 * jumping to Diagnostics — for a value nobody measured. The motor is the only signal the
 * diagnostics unit actually publishes over SOME/IP, so it is the only one graded at all.
 */
class VehicleSeverityFlow(
    source: VehicleDataSource,
    private val resolver: SeverityResolver = SeverityResolver(),
) {
    private val motor: Flow<SignalState<MotorTelemetry>> = source.motor

    /** Hotspot -> Severity. Every hotspot but [Hotspot.MOTOR] is always null; motor is null
     *  only when its own signal isn't live (offline/loading/stale-ignored). */
    val severities: Flow<Map<Hotspot, Severity?>> = motor.map { state ->
        mapOf(
            Hotspot.BATTERY to null,
            Hotspot.MOTOR to state.latestValueOrNull?.let { resolver.severityFor(Hotspot.MOTOR, motor = it) },
            Hotspot.BRAKES to null,
            Hotspot.DOORS to null,
            Hotspot.TIRE_FL to null,
            Hotspot.TIRE_FR to null,
            Hotspot.TIRE_RL to null,
            Hotspot.TIRE_RR to null,
        )
    }

    /**
     * Health score 0..100 for the ring, driven by [Severity] (docs/09 §2.3: "Severity drives the
     * hotspot dot colour, the health-ring score, the alert list and the card simultaneously").
     * Null (the ring reads "waiting") until the diagnostics unit has published a severity at all.
     *
     * Was `remainingLife.hours / a 4-month assumption` — docs/09 §2 is explicit that `percent`
     * "is never derived from hours against an assumed design life: the bar it draws would be a
     * claim nobody made", and the ring is exactly that kind of claim. It also silently broke on a
     * unit's own hours estimate arriving small (e.g. a months figure never converted to hours
     * upstream): a live case showed `remainingLife.percent` correctly at 81 while this formula's
     * `hours` was 3.25, giving `(3.25 / 2880) * 100 -> 0` -- the ring read zero while the unit was
     * reporting 81% healthy.
     */
    val healthScore: Flow<Int?> = motor.map { state ->
        state.latestValueOrNull?.faultSeverity?.let(::scoreFor)
    }

    fun stateIn(scope: CoroutineScope): StateFlow<Map<Hotspot, Severity?>> =
        severities.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private companion object {
        /** Round numbers, not a measurement: there is no unit-reported "how healthy" figure at
         *  the OK/CAUTION/CRITICAL granularity, only which of the three it is. */
        fun scoreFor(severity: Severity): Int = when (severity) {
            Severity.OK -> 100
            Severity.CAUTION -> 60
            Severity.CRITICAL -> 20
        }
    }
}
