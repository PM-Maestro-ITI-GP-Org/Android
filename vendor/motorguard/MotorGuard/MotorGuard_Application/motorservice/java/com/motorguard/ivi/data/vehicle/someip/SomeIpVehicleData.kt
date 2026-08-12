package com.motorguard.ivi.data.vehicle.someip

import android.util.Log
import com.motorguard.ivi.data.vehicle.api.BatteryTelemetry
import com.motorguard.ivi.data.vehicle.api.BrakeTelemetry
import com.motorguard.ivi.data.vehicle.api.DoorsTelemetry
import com.motorguard.ivi.data.vehicle.api.MotorCaptureSource
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.SignalState
import com.motorguard.ivi.data.vehicle.api.TireTelemetry
import com.motorguard.ivi.data.vehicle.api.VehicleDataSource
import com.motorguard.ivi.data.vehicle.api.VehicleMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The real motor signal, wrapped around whatever was providing the rest of the car.
 *
 * The diagnostics unit publishes the motor and nothing else — there is no battery, tyre, brake or
 * door sensor on this vehicle, which is why docs/09 describes one signal and one method and stops.
 * Forcing the other five to [SignalState.Offline] would blank most of the diagnostics screen to
 * make a point about data this link was never going to carry, so they keep coming from the
 * delegate. The motor, and only the motor, now comes off the wire.
 */
internal class SomeIpVehicleDataSource(
    private val delegate: VehicleDataSource,
    private val link: SomeIpMotorLink,
) : VehicleDataSource {

    override val motor: StateFlow<SignalState<MotorTelemetry>> = link.motor

    override val battery: StateFlow<SignalState<BatteryTelemetry>> get() = delegate.battery
    override val brakes: StateFlow<SignalState<BrakeTelemetry>> get() = delegate.brakes
    override val tires: StateFlow<List<SignalState<TireTelemetry>>> get() = delegate.tires
    override val doors: StateFlow<SignalState<DoorsTelemetry>> get() = delegate.doors
    override val metrics: StateFlow<SignalState<VehicleMetrics>> get() = delegate.metrics

    override fun reconnect() {
        link.reconnect()
        delegate.reconnect()
    }
}

/**
 * Builds the SOME/IP-backed pair, or nothing.
 *
 * `null` is a normal answer, not an error: it is what a build without the native library gets, and
 * the caller keeps the source it already had. That is what makes this file safe to reference from
 * `VehicleData` in a tree where `libmotorguardsomeip.so` may not have been built.
 */
internal object SomeIpVehicleData {

    fun create(
        scope: CoroutineScope,
        fallback: VehicleDataSource,
    ): Pair<VehicleDataSource, MotorCaptureSource>? {
        if (!MotorLinkNative.available) return null

        val config = MotorLinkConfig.fromSystemProperties()
        val link = SomeIpMotorLink(scope, config)
        if (!link.opened) {
            Log.e(MotorLinkNative.TAG, "link did not open; staying on the existing source")
            link.close()
            return null
        }

        Log.i(
            MotorLinkNative.TAG,
            "motor signal from service %04x.%04x, capture method %04x".format(
                config.serviceId, config.instanceId, config.captureMethodId,
            ),
        )
        return SomeIpVehicleDataSource(fallback, link) to SomeIpMotorCaptureSource(link, config)
    }
}
