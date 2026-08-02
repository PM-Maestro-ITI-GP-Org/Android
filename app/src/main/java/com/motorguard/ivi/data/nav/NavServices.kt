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
