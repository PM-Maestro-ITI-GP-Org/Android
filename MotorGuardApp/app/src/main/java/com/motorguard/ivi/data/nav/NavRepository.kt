package com.motorguard.ivi.data.nav

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * The navigation feature's data entry point. Mirrors the role `CarDataRepository` plays for the
 * vehicle fragments: the ViewModel talks only to this, and never to a provider directly.
 *
 * It also owns the one piece of derived state worth caching — the cumulative distance table for
 * the active route. Guidance needs it on every position update (10 Hz), and recomputing a few
 * hundred haversines per tick on the Pi would be pure waste.
 */
class NavRepository(context: Context) {

    private val providers = NavProviders.of(context)

    private var activeRoute: Route? = null
    private var activeCumulative: DoubleArray = DoubleArray(0)

    /** Positions from whichever [LocationSource] [NavConfig] selected. Cold. */
    fun positions(): Flow<VehiclePosition> = providers.location.positions()

    suspend fun search(query: String, near: GeoPoint?): List<Place> =
        providers.geocoding.search(query, near)

    suspend fun routes(from: GeoPoint, to: Place): List<Route> =
        providers.routing.routes(from, to)

    /**
     * Make [route] the one being followed. Caches its distance table and hands it to the
     * location source (which the simulator needs and real GNSS ignores).
     */
    fun startGuidance(route: Route) {
        activeRoute = route
        activeCumulative = RouteMath.cumulativeDistances(route.shape)
        providers.location.setRoute(route)
    }

    fun stopGuidance() {
        activeRoute = null
        activeCumulative = DoubleArray(0)
        providers.location.setRoute(null)
    }

    /**
     * Progress of [position] along the active route, or null when nothing is being followed.
     *
     * The simulated source is already exactly on the polyline, but snapping anyway is what
     * makes real GNSS work unchanged: a fix 8 m off the road still resolves to the right
     * distance-along, and therefore the right maneuver.
     */
    fun progressFor(position: VehiclePosition): NavProgress? {
        val route = activeRoute ?: return null
        if (activeCumulative.isEmpty()) return null
        val along = RouteMath.snapToRoute(route.shape, activeCumulative, position.point)
        return RouteMath.progress(route, along, activeCumulative)
    }
}
