package com.motorguard.ivi.data.nav

import android.content.Context
import com.motorguard.ivi.data.nav.google.GoogleDirectionsRoutingService
import com.motorguard.ivi.data.nav.google.GooglePlacesGeocodingService
import com.motorguard.ivi.data.nav.oss.AndroidLocationSource
import com.motorguard.ivi.data.nav.oss.PhotonGeocodingService
import com.motorguard.ivi.data.nav.oss.SimulatedLocationSource
import com.motorguard.ivi.data.nav.oss.ValhallaRoutingService

/**
 * The only place in the app that names a concrete navigation provider.
 *
 * This is hand-rolled service location rather than Hilt/Koin on purpose: the project has no DI
 * framework yet, and adding one for three objects would be a decision imposed on the other
 * four fragment owners. When [com.motorguard.ivi.data.CarDataRepository] arrives with a real
 * DI graph, this object collapses into a module without any caller changing.
 */
object NavProviders {

    @Volatile
    private var cached: Bundle? = null

    /** The provider set selected by [NavConfig]. Built once, then reused across tab switches. */
    fun of(context: Context): Bundle {
        val existing = cached
        if (existing != null && existing.matchesConfig()) return existing
        return synchronized(this) {
            val current = cached
            if (current != null && current.matchesConfig()) {
                current
            } else {
                build(context.applicationContext).also { cached = it }
            }
        }
    }

    /** Drop the cached providers — call after changing [NavConfig] at runtime. */
    fun invalidate() {
        synchronized(this) { cached = null }
    }

    private fun build(appContext: Context): Bundle = Bundle(
        stack = NavConfig.stack,
        locationMode = NavConfig.locationMode,
        geocoding = when (NavConfig.stack) {
            NavConfig.Stack.OSS -> PhotonGeocodingService()
            NavConfig.Stack.GOOGLE -> GooglePlacesGeocodingService()
        },
        routing = when (NavConfig.stack) {
            NavConfig.Stack.OSS -> ValhallaRoutingService()
            NavConfig.Stack.GOOGLE -> GoogleDirectionsRoutingService()
        },
        location = when (NavConfig.locationMode) {
            NavConfig.LocationMode.SIMULATED -> SimulatedLocationSource()
            NavConfig.LocationMode.GNSS -> AndroidLocationSource(appContext)
        },
    )

    /** One resolved provider set. */
    class Bundle(
        private val stack: NavConfig.Stack,
        private val locationMode: NavConfig.LocationMode,
        val geocoding: GeocodingService,
        val routing: RoutingService,
        val location: LocationSource,
    ) {
        internal fun matchesConfig(): Boolean =
            stack == NavConfig.stack && locationMode == NavConfig.locationMode
    }
}
