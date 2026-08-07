package com.motorguard.ivi.data.vehicle.api

/** Card fields per docs/05-diagnostics.md §"Hotspot behavior". */

data class BatteryTelemetry(
    val chargePercent: Float,
    val cellTempC: Float,
    val healthPercent: Float,
    val cycleCount: Int,
    val charging: Boolean,
)

data class TireTelemetry(
    val corner: Hotspot,
    val psi: Float,
    val tempC: Float,
)

data class MotorTelemetry(
    val loadPercent: Float,
    val tempC: Float,
    val rpm: Int,
)

data class BrakeTelemetry(
    val padWearPercent: Float,
    val fluidOk: Boolean,
)

/** One door's state; the DOORS hotspot aggregates all of these into [DoorsTelemetry]. */
data class DoorState(
    val door: Door,
    val open: Boolean,
    val locked: Boolean,
)

enum class Door(val label: String) { FL("Front left"), FR("Front right"), RL("Rear left"), RR("Rear right") }

data class DoorsTelemetry(val doors: List<DoorState>) {
    val anyOpen: Boolean get() = doors.any { it.open }
    val anyUnlocked: Boolean get() = doors.any { !it.locked }
}

data class VehicleMetrics(
    val speedKmh: Float = 0f,
    val odometerKm: Float = 0f,
)
