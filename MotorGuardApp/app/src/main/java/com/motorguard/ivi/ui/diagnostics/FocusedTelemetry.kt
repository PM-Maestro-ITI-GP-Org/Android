package com.motorguard.ivi.ui.diagnostics

import com.motorguard.ivi.data.vehicle.api.BatteryTelemetry
import com.motorguard.ivi.data.vehicle.api.BrakeTelemetry
import com.motorguard.ivi.data.vehicle.api.DoorsTelemetry
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import com.motorguard.ivi.data.vehicle.api.TireTelemetry

/*
 * Telemetry for whichever component is currently focused, already graded.
 *
 * A "Reading" pairs one telemetry sample with the severities derived FROM THAT SAME SAMPLE, so a
 * value can never be drawn next to a grade computed from a different tick. The grading itself is
 * pure delegation to the domain's SeverityResolver — no threshold number appears anywhere in the
 * UI layer (phase-1 brief §15: severity logic lives in SeverityEvaluator and is never duplicated).
 *
 * Fields with no rule in SeverityThresholds — motor RPM, battery cycle count and the charging flag
 * — carry no severity at all rather than an invented one. They render neutral.
 */

internal data class BatteryReading(
    val data: BatteryTelemetry,
    val charge: Severity,
    val cellTemp: Severity,
    val health: Severity,
) {
    val overall: Severity get() = maxOf(charge, cellTemp, health)
}

internal data class TireReading(
    val data: TireTelemetry,
    val psi: Severity,
    val temp: Severity,
) {
    val overall: Severity get() = maxOf(psi, temp)
}

internal data class MotorReading(
    val data: MotorTelemetry,
    val load: Severity,
    val temp: Severity,
) {
    val overall: Severity get() = maxOf(load, temp)
}

internal data class BrakeReading(
    val data: BrakeTelemetry,
    val wear: Severity,
    val fluid: Severity,
) {
    val overall: Severity get() = maxOf(wear, fluid)
}

internal data class DoorsReading(val data: DoorsTelemetry, val overall: Severity)

/**
 * The focused component's data, as one closed set of possibilities.
 *
 * Making the variant carry the hotspot family is what forbids the states that would otherwise need
 * defending against: battery focused with tire data supplied, or an `Offline` signal that still
 * carries numbers. `SignalState.Offline` and `.Loading` are `data object`s with no payload, so an
 * offline card has literally no value available to leak — the spec's "never render a fabricated or
 * silently-stale value as if it were live" is enforced by the type, not by discipline.
 */
internal sealed interface FocusedTelemetry {
    val hotspot: Hotspot

    data class Battery(val signal: SignalState<BatteryReading>) : FocusedTelemetry {
        override val hotspot: Hotspot get() = Hotspot.BATTERY
    }

    data class Motor(val signal: SignalState<MotorReading>) : FocusedTelemetry {
        override val hotspot: Hotspot get() = Hotspot.MOTOR
    }

    data class Brakes(val signal: SignalState<BrakeReading>) : FocusedTelemetry {
        override val hotspot: Hotspot get() = Hotspot.BRAKES
    }

    data class Doors(val signal: SignalState<DoorsReading>) : FocusedTelemetry {
        override val hotspot: Hotspot get() = Hotspot.DOORS
    }

    /** [hotspot] is always one of [Hotspot.tireCorners]. */
    data class Tire(
        override val hotspot: Hotspot,
        val signal: SignalState<TireReading>,
    ) : FocusedTelemetry
}

/**
 * [SignalState] is a functor over its payload. `Loading` and `Offline` are `SignalState<Nothing>`
 * and pass straight through, which is precisely what keeps "offline carries no number" structural.
 */
internal inline fun <T, R> SignalState<T>.mapData(transform: (T) -> R): SignalState<R> = when (this) {
    is SignalState.Live -> SignalState.Live(transform(data), timestampMs)
    is SignalState.Stale -> SignalState.Stale(transform(lastData), lastTimestampMs)
    SignalState.Loading -> SignalState.Loading
    SignalState.Offline -> SignalState.Offline
}

/**
 * The ONLY place the tires list is indexed. `indexOf` returns -1 for a non-corner hotspot and
 * `getOrNull(-1)` is null, so a caller mistake degrades to a loading card rather than throwing.
 */
internal fun List<SignalState<TireTelemetry>>.forCorner(
    corner: Hotspot,
): SignalState<TireTelemetry> =
    getOrNull(Hotspot.tireCorners.indexOf(corner)) ?: SignalState.Loading

/**
 * True when two snapshots would produce identical pixels.
 *
 * Live timestamps are deliberately ignored: nothing on a live card renders one, and the fake source
 * republishes every flow several times a second with a fresh timestamp and an unchanged payload —
 * comparing them would recompose the card ~5 Hz for numbers that never moved. Stale timestamps are
 * NOT ignored, because the age badge renders them and must keep ticking.
 */
internal fun FocusedTelemetry?.rendersSameAs(other: FocusedTelemetry?): Boolean = when {
    this == null || other == null -> this == null && other == null
    hotspot != other.hotspot -> false
    this is FocusedTelemetry.Battery && other is FocusedTelemetry.Battery ->
        signal.sameRender(other.signal)
    this is FocusedTelemetry.Motor && other is FocusedTelemetry.Motor ->
        signal.sameRender(other.signal)
    this is FocusedTelemetry.Brakes && other is FocusedTelemetry.Brakes ->
        signal.sameRender(other.signal)
    this is FocusedTelemetry.Doors && other is FocusedTelemetry.Doors ->
        signal.sameRender(other.signal)
    this is FocusedTelemetry.Tire && other is FocusedTelemetry.Tire ->
        signal.sameRender(other.signal)
    else -> false
}

private fun <T> SignalState<T>.sameRender(other: SignalState<T>): Boolean = when {
    this is SignalState.Live && other is SignalState.Live -> data == other.data
    this is SignalState.Stale && other is SignalState.Stale ->
        lastData == other.lastData && lastTimestampMs == other.lastTimestampMs
    else -> this == other // Loading and Offline are data objects
}
