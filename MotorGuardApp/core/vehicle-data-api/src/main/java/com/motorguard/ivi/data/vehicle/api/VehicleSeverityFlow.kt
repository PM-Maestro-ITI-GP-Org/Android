package com.motorguard.ivi.data.vehicle.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Combines all telemetry flows into the per-hotspot severity map the UI renders
 * (dot color, card banner, alert candidates). Severity of an offline/loading
 * signal is not fabricatable, so it maps to null -> UI shows grey + "No data".
 */
class VehicleSeverityFlow(
    source: VehicleDataSource,
    private val resolver: SeverityResolver = SeverityResolver(),
) {
    private data class Snapshot(
        val battery: SignalState<BatteryTelemetry>,
        val motor: SignalState<MotorTelemetry>,
        val brakes: SignalState<BrakeTelemetry>,
        val tires: List<SignalState<TireTelemetry>>,
        val doors: SignalState<DoorsTelemetry>,
    )

    private val snapshot = combine(
        source.battery, source.motor, source.brakes, source.tires, source.doors,
        ::Snapshot,
    )

    /** Hotspot -> Severity, or null when the signal isn't live (offline/loading/stale-ignored). */
    val severities: kotlinx.coroutines.flow.Flow<Map<Hotspot, Severity?>> =
        kotlinx.coroutines.flow.flow {
            snapshot.collect { s ->
                emit(mapOf(
                    Hotspot.BATTERY to (s.battery.latestValueOrNull?.let {
                        resolver.severityFor(Hotspot.BATTERY, battery = it)
                    }),
                    Hotspot.MOTOR to (s.motor.latestValueOrNull?.let {
                        resolver.severityFor(Hotspot.MOTOR, motor = it)
                    }),
                    Hotspot.BRAKES to (s.brakes.latestValueOrNull?.let {
                        resolver.severityFor(Hotspot.BRAKES, brakes = it)
                    }),
                    Hotspot.DOORS to (s.doors.latestValueOrNull?.let {
                        resolver.severityFor(Hotspot.DOORS, doorsState = it)
                    }),
                    Hotspot.TIRE_FL to tireSeverity(s, Hotspot.TIRE_FL),
                    Hotspot.TIRE_FR to tireSeverity(s, Hotspot.TIRE_FR),
                    Hotspot.TIRE_RL to tireSeverity(s, Hotspot.TIRE_RL),
                    Hotspot.TIRE_RR to tireSeverity(s, Hotspot.TIRE_RR),
                ))
            }
        }

    /**
     * Health score 0..100 for the ring: the motor's remaining useful life as a fraction of a
     * 4-month full-life assumption. Null (the ring reads "waiting") until the diagnostics unit
     * has published a remaining-life estimate at all — no other hotspot has one, so there is
     * nothing else to derive a score from.
     */
    val healthScore: kotlinx.coroutines.flow.Flow<Int?> =
        kotlinx.coroutines.flow.flow {
            snapshot.collect { s ->
                val life = s.motor.latestValueOrNull?.remainingLife
                emit(life?.let { ((it.hours / FULL_LIFE_HOURS) * 100f).toInt().coerceIn(0, 100) })
            }
        }

    fun stateIn(scope: CoroutineScope): StateFlow<Map<Hotspot, Severity?>> =
        severities.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun tireSeverity(s: Snapshot, corner: Hotspot): Severity? =
        s.tires.getOrNull(Hotspot.tireCorners.indexOf(corner))
            ?.latestValueOrNull
            ?.let { resolver.severityFor(corner, tirePsi = it.psi, tireTempC = it.tempC) }

    private companion object {
        /** A 4-month full life, in hours, at a flat 30-day month. */
        const val FULL_LIFE_HOURS = 4 * 30 * 24f
    }
}
