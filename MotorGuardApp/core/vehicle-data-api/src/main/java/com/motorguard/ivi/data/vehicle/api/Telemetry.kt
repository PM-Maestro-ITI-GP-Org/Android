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
 * Motor summary, computed on the diagnostics unit and pushed at ~1 Hz.
 *
 * Deliberately small. The raw signals behind these numbers are sampled at 20 kHz across thirteen
 * channels — around a megabyte a second — which is why the transport carries this handful of
 * derived scalars continuously and the raw samples only on request (see [MotorCaptureSummary]).
 *
 * There is no load or temperature here because this vehicle has no sensor for either. Anything the
 * card shows has to come from the signals that actually exist.
 */
data class MotorTelemetry(
    val rpm: Int,
    val powerKw: Float,
    val dcBusVolts: Float,
    val faultType: MotorFaultType,
    /**
     * The classifier's own severity, already expressed in this app's vocabulary so the dot, the
     * health ring, the alert list and the card cannot disagree about how bad the motor is. The
     * mapping from whatever the diagnostics unit sends belongs in the transport adapter, not here.
     */
    val faultSeverity: Severity,
    val remainingLife: RemainingLife?,
    /** The most recent on-demand capture, or null when none has been requested this session. */
    val capture: MotorCaptureSummary? = null,
)

/**
 * What one requested capture of the raw 20 kHz signals reduces to.
 *
 * Each field except [averagePowerKw] is the evidence for one [MotorFaultType], which is what lets
 * the card show why it is claiming a fault rather than only asserting one:
 * [currentImbalancePercent] for [MotorFaultType.ELECTRICAL], [vibrationRmsG] for
 * [MotorFaultType.MECHANICAL], [speedTrackingErrorPercent] for [MotorFaultType.SENSOR].
 *
 * [capturedAtMs] is not decoration. These are a snapshot of a window that has already passed, and
 * a card showing them next to live values must say how old they are or it is implying they are
 * current.
 */
data class MotorCaptureSummary(
    val capturedAtMs: Long,
    val averagePowerKw: Float,
    val currentImbalancePercent: Float,
    val vibrationRmsG: Float,
    val speedTrackingErrorPercent: Float,
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
