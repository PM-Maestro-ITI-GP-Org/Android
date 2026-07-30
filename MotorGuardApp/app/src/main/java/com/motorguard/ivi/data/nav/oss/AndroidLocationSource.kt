package com.motorguard.ivi.data.nav.oss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.LocationSource
import com.motorguard.ivi.data.nav.VehiclePosition
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt

/**
 * Real positions from the platform [LocationManager] — the drop-in replacement for
 * [SimulatedLocationSource] once a GNSS receiver is attached to the Pi.
 *
 * Deliberately *not* FusedLocationProvider: that lives in Play Services, which an AOSP
 * automotive image does not have. Plain `LocationManager` is the AOSP-safe API.
 *
 * To switch over:
 *  1. `NavConfig.locationMode = LocationMode.GNSS`
 *  2. grant `ACCESS_FINE_LOCATION` (already declared in the manifest)
 *  3. on a real vehicle build, prefer the car's own fix if the VHAL exposes one.
 *
 * Emits nothing (rather than throwing) when the permission is missing or no provider is
 * enabled, so the Nav tab degrades to "position unknown" instead of crashing.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    override fun positions(): Flow<VehiclePosition> {
        if (!hasPermission()) return flow { }

        return callbackFlow {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@callbackFlow run { close() }

            // Explicit object, not a SAM lambda. `onStatusChanged` / `onProviderEnabled` /
            // `onProviderDisabled` only became default methods in API 30; against minSdk 29 a
            // lambda compiles fine and then throws AbstractMethodError on a real API 29 device.
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(location.toVehiclePosition())
                }

                @Deprecated("Required by LocationListener below API 30")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            if (providers.isEmpty()) return@callbackFlow run { close() }

            try {
                providers.forEach { provider ->
                    manager.requestLocationUpdates(
                        provider,
                        MIN_INTERVAL_MS,
                        MIN_DISTANCE_M,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
                // Seed with the last known fix so guidance does not wait for the first update.
                providers.firstNotNullOfOrNull { provider ->
                    @Suppress("MissingPermission") // guarded by hasPermission() above
                    manager.getLastKnownLocation(provider)
                }?.let { trySend(it.toVehiclePosition()) }
            } catch (_: SecurityException) {
                close()
            }

            awaitClose { manager.removeUpdates(listener) }
        }
    }

    private fun Location.toVehiclePosition() = VehiclePosition(
        point = GeoPoint(latitude, longitude),
        // A stationary fix reports a meaningless bearing; hold north rather than spin.
        bearingDegrees = if (hasBearing() && speed > MIN_BEARING_SPEED_MPS) bearing else 0f,
        speedKph = (speed * 3.6f).roundToInt(),
    )

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MIN_INTERVAL_MS = 500L
        const val MIN_DISTANCE_M = 0f

        /** Below ~1 m/s the reported bearing is noise. */
        const val MIN_BEARING_SPEED_MPS = 1.0f
    }
}
