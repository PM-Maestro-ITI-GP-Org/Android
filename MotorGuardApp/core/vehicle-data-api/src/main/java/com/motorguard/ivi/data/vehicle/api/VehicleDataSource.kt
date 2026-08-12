package com.motorguard.ivi.data.vehicle.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The ONLY seam the diagnostics UI touches. Phase 1 gets [FakeVehicleDataSource];
 * Phase 2 swaps in CarDataRepository (backed by CarPropertyManager) via DI — no UI
 * change. Emission-driven only: subscribers see StateFlows, nobody polls.
 *
 * There is exactly one [VehicleDataSource] instance per app (view-models observe
 * the shared flows).
 */
interface VehicleDataSource {
    val battery: StateFlow<SignalState<BatteryTelemetry>>
    val motor: StateFlow<SignalState<MotorTelemetry>>
    val brakes: StateFlow<SignalState<BrakeTelemetry>>

    /** Exactly 4 entries, in [Hotspot.tireCorners] order. */
    val tires: StateFlow<List<SignalState<TireTelemetry>>>

    /** Single aggregated hotspot, holds the per-door list. */
    val doors: StateFlow<SignalState<DoorsTelemetry>>

    val metrics: StateFlow<SignalState<VehicleMetrics>>

    /** The only "write" the diagnostics surface is allowed (still read-only vehicle-wise). */
    fun reconnect() {}
}

/** Derives the current severity of each hotspot from live data + hysteresis. */
fun VehicleDataSource.severityFlow(resolver: SeverityResolver) =
    VehicleSeverityFlow(this, resolver)
