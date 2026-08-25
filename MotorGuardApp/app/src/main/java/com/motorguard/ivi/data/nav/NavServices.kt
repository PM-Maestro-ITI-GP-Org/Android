package com.motorguard.ivi.data.nav

import kotlinx.coroutines.flow.Flow

/**
 * The three seams of the navigation feature. Everything above these interfaces (ViewModel,
 * screens, map surface) is provider-neutral; everything below is one swappable file.
 *
 * Current wiring lives in [NavProviders]:
 *   search  → Photon      (OSS, komoot)          ← or Google Places
 *   routes  → Valhalla    (OSS, FOSSGIS/self-host) ← or Google Directions
 *   position→ Simulated   (demo)                 ← or Android GNSS / VHAL
 */

/** Destination search. Called on every keystroke (debounced by the ViewModel). */
interface GeocodingService {
    /**
     * @param near bias results towards the car's position when known.
     * @throws Exception on transport/parse failure — the ViewModel turns that into a banner.
     */
    suspend fun search(query: String, near: GeoPoint?): List<Place>

    /**
     * Features of a given kind close to a point, nearest first.
     *
     * A different question from [search] and not answerable with it. [search] is a *geocoder*:
     * it matches text against names worldwide and treats `near` as a ranking bias, so
     * "petrol station" returns things called that, anywhere, and the closest one to the car may
     * not be in the results at all. Asking for `amenity=fuel` within a radius is a query about
     * the map rather than about names.
     *
     * @param osmTags `key:value` filters, OR-ed — e.g. `amenity:fuel`.
     * @param radiusKm hard limit. Nothing beyond it is a result; it is not a bias.
     * @return nearest first, or empty when there is genuinely nothing in range.
     */
    suspend fun nearby(
        osmTags: List<String>,
        near: GeoPoint,
        radiusKm: Int,
    ): List<Place> = emptyList()
}

/** Route computation. Returns the preferred route first, then alternates. */
interface RoutingService {
    suspend fun routes(from: GeoPoint, to: Place): List<Route>
}

/**
 * Where the car is. Implementations are cold: [positions] starts producing when collected
 * and stops when the collector goes away, so nothing polls while the Nav tab is hidden.
 */
interface LocationSource {
    fun positions(): Flow<VehiclePosition>

    /**
     * Hand the active route to sources that need it. The simulator drives along it; a real
     * GNSS receiver ignores it entirely, which is why this has a default no-op body.
     */
    fun setRoute(route: Route?) {}
}
