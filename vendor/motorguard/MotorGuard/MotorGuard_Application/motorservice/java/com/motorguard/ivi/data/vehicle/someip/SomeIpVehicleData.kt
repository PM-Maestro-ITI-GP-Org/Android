package com.motorguard.ivi.data.vehicle.someip

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

        val config = MotorLinkConfig.fromSystemProperties().copy(
            androidNetworkHandle = resolveEthernetNetworkHandle(),
        )
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

    /**
     * The Ethernet [android.net.Network]'s handle, synchronously — a board with both Wi-Fi and
     * Ethernet up otherwise leaves every SD/event/capture socket on whatever ConnectivityManager
     * calls the default network, which is Wi-Fi, and the diagnostics unit is never on Wi-Fi. See
     * [MotorLinkConfig.androidNetworkHandle] and motor_link.cpp's android_setsocknetwork() calls.
     *
     * `allNetworks` rather than `registerNetworkCallback`: this runs once, synchronously, at
     * `create()` — a plain object with no coroutine to suspend and no callback to unregister
     * later — and the Ethernet interface here comes up during boot, long before this class is
     * ever touched, so the network this asks for is already known to ConnectivityManager by the
     * time the app starts.
     *
     * 0 (`NETWORK_UNSPECIFIED`) on anything that goes wrong: no Context yet, no
     * ConnectivityManager, no Ethernet transport currently up. The link still opens on the
     * default network exactly as it did before this existed — see [bindToNetwork] in
     * motor_link.cpp — so a board with only Wi-Fi, or a Gradle/emulator build, is unaffected.
     */
    private fun resolveEthernetNetworkHandle(): Long {
        val context = currentApplicationContext() ?: return 0L
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks
                .firstOrNull { net ->
                    cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                }
                ?.networkHandle
        }.onFailure {
            Log.w(MotorLinkNative.TAG, "could not resolve an Ethernet network (${it.message}); " +
                "sockets will use the default network")
        }.getOrNull() ?: 0L
    }

    /**
     * `ActivityThread.currentApplication()` by reflection — the same reason
     * [MotorLinkConfig.fromSystemProperties] reaches `SystemProperties` the same way: [VehicleData]
     * is a plain object with no Application subclass to hang a Context off (the spec forbids
     * touching the manifest), and every process running this code already has one by the time
     * `create()` runs.
     */
    private fun currentApplicationContext(): Context? = runCatching {
        val cls = Class.forName("android.app.ActivityThread")
        cls.getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()
}
