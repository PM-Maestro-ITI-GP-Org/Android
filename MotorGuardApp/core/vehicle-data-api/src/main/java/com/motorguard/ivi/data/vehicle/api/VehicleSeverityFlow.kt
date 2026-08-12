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

    /** Health score 0..100 for the ring: 100 when everything OK, sinking toward 0 with worst severity. */
    val healthScore: kotlinx.coroutines.flow.Flow<Int?> =
        kotlinx.coroutines.flow.flow {
            severities.collect { map ->
                val present = map.values.filterNotNull()
                if (present.isEmpty()) emit(null)
                else {
                    val worst = present.maxBy { it.ordinal }
                    val cautions = present.count { it == Severity.CAUTION }
                    val criticals = present.count { it == Severity.CRITICAL }
                    emit((100 - cautions * 8 - criticals * 30 - if (worst == Severity.CRITICAL) 20 else 0)
                        .coerceIn(0, 100))
                }
            }
        }

    fun stateIn(scope: CoroutineScope): StateFlow<Map<Hotspot, Severity?>> =
        severities.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun tireSeverity(s: Snapshot, corner: Hotspot): Severity? =
        s.tires.getOrNull(Hotspot.tireCorners.indexOf(corner))
            ?.latestValueOrNull
            ?.let { resolver.severityFor(corner, tirePsi = it.psi, tireTempC = it.tempC) }
}
