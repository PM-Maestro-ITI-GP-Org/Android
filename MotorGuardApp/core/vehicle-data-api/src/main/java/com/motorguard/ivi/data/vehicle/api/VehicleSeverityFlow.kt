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
     * Health score 0..100, shared by every "Vehicle health" figure in the app (the Home card and
     * the Diagnostics ring both collect this exact flow rather than each computing their own, so
     * the two screens cannot disagree about the number): the motor's remaining useful life as a
     * fraction of a 4-month full-life assumption. Null ("waiting for telemetry") until the
     * diagnostics unit has published a remaining-life estimate at all.
     *
     * Briefly replaced with a Severity-derived score (docs/09 §2.3) after a live case showed this
     * formula reading 0 while the unit reported 81% healthy -- root cause was upstream, on the
     * diagnostics unit: its RUL parser grabbed the first number in the model's sentence (the
     * health score, "1.00") instead of the actual RUL figure ("RUL 4.00 months"), so `hours`
     * arrived as 1 instead of ~2922 (motor_diag_service commit 81756d4 fixes the parser). With
     * that fixed at the source, this is back to the RUL/4-month figure by request.
     */
    val healthScore: Flow<Int?> = motor.map { state ->
        val life = state.latestValueOrNull?.remainingLife
        life?.let { ((it.hours / FULL_LIFE_HOURS) * 100f).toInt().coerceIn(0, 100) }
    }

    fun stateIn(scope: CoroutineScope): StateFlow<Map<Hotspot, Severity?>> =
        severities.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private companion object {
        /** A 4-month full life, in hours, at a flat 30-day month. */
        const val FULL_LIFE_HOURS = 4 * 30 * 24f
    }
}
