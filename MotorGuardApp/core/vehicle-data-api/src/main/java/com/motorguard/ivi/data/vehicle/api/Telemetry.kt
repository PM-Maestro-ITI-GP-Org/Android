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

/**
 * How the motor is failing, as classified by the diagnostics unit — not something this app infers.
 *
 * [NORMAL] is a real answer, not the absence of one: it means the classifier ran and found nothing.
 * The UI renders no fault block for it rather than a "no fault" badge, the same way an offline
 * signal renders no number rather than a dash.
 */
enum class MotorFaultType { NORMAL, ELECTRICAL, MECHANICAL, SENSOR }

/**
 * How much life the diagnostics unit thinks the motor has left.
 *
 * [hours] is the number the driver acts on. [percent] is optional because a model can be confident
 * about a fraction of life while being unwilling to commit to an absolute figure, and a percentage
 * invented from hours against an assumed design life would be a claim this app is not entitled to
 * make. Null simply means no bar is drawn.
 */
data class RemainingLife(
    val hours: Float,
    val percent: Float? = null,
)

/**
 * What the diagnostics unit pushes about the motor, and all it pushes.
 *
 * Three fields, deliberately. Everything measurable — speed, power, currents, vibration — is
 * derived from the raw signals, and those are only ever fetched on request (see [MotorCapture]).
 * Publishing a running speed and power alongside would mean a second, continuous stream of numbers
 * that duplicates what a capture already answers better, for a motor whose interesting behaviour
 * is not visible at 1 Hz anyway.
 *
 * What remains is what cannot wait for a request: whether something is wrong, how badly, and how
 * long there is left. Those drive the hotspot dot, the health ring and the alert list, which have
 * to be right without the user having asked for anything.
 */
data class MotorTelemetry(
    val faultType: MotorFaultType,
    /**
     * The classifier's own severity, already expressed in this app's vocabulary so the dot, the
     * health ring, the alert list and the card cannot disagree about how bad the motor is. The
     * mapping from whatever the diagnostics unit sends belongs in the transport adapter, not here.
     */
    val faultSeverity: Severity,
    val remainingLife: RemainingLife?,
)

/**
 * Everything one capture says about the motor, reduced to numbers.
 *
 * Computed by [MotorCapture.summarise] from the samples themselves, not reported by the vehicle:
 * these exist because the user asked for a capture, and they describe that capture's window rather
 * than the motor right now. [capturedAtMs] is what keeps that distinction visible on the card.
 *
 * Three of these fields are evidence for one fault type each — [currentImbalancePercent] for
 * [MotorFaultType.ELECTRICAL], [vibrationRmsG] for [MotorFaultType.MECHANICAL],
 * [speedTrackingErrorPercent] for [MotorFaultType.SENSOR] — which is what lets the card show why
 * a fault is being claimed rather than only that it is.
 */
data class MotorCaptureSummary(
    val capturedAtMs: Long,
    val windowSec: Float,
    val averageSpeedRpm: Float,
    val maxSpeedRpm: Float,
    val averagePowerW: Float,
    val peakPowerW: Float,
    val energyWh: Float,
    val rmsCurrentA: Float,
    val currentImbalancePercent: Float,
    val vibrationRmsG: Float,
    val speedTrackingErrorPercent: Float,
    val averageDcBusVolts: Float,
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
