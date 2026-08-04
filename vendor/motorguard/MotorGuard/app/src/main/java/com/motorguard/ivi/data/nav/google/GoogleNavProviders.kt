package com.motorguard.ivi.data.nav.google

import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.GeocodingService
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.data.nav.Route
import com.motorguard.ivi.data.nav.RoutingService

/**
 * The Google backup path — **declared, not implemented**.
 *
 * Why it is empty rather than absent: the point of the [GeocodingService] / [RoutingService]
 * seam is that the OSS stack is a choice, not an assumption. These two classes are where a
 * Google implementation goes, and the fact that they compile against the same interfaces is
 * the proof that swapping is a one-file job rather than a rewrite.
 *
 * Why it cannot work on this target today: the Maps SDK, Directions API and Places API all
 * arrive through Google Play Services, which an AOSP automotive image does not ship. Running
 * this path means either building a GMS-certified image or calling the web APIs directly over
 * HTTPS (which *is* possible from AOSP — see the TODOs below).
 *
 * To bring it up:
 *  1. `NavConfig.stack = Stack.GOOGLE`
 *  2. add an API key (never hardcode it — `local.properties` → `buildConfigField`)
 *  3. fill in the two `TODO`s using [com.motorguard.ivi.data.nav.NavHttp] exactly the way
 *     `oss/ValhallaRoutingService` and `oss/PhotonGeocodingService` do; the response mapping
 *     is the only real work, and the maneuver table below is the fiddly part
 *  4. for rendering, add a `GoogleMapSurface` alongside `MapLibreMapSurface` implementing the
 *     same `MapSurface` contract, and a `MapBackend.GOOGLE_MAPS` case.
 *
 * Both classes throw rather than silently returning empty lists: a misconfigured stack should
 * be loud in logcat, not a search box that mysteriously never finds anything.
 */

/** Places Autocomplete + Place Details. See the class KDoc above before implementing. */
class GooglePlacesGeocodingService : GeocodingService {
    override suspend fun search(query: String, near: GeoPoint?): List<Place> =
        TODO(
            "Google Places is not wired up. GET " +
                "maps.googleapis.com/maps/api/place/autocomplete/json?input=…&location=lat,lon&key=… " +
                "then resolve each prediction's place_id via /place/details/json to get its " +
                "geometry.location, and map onto Place(name, subtitle, point, category). " +
                "Set NavConfig.stack = OSS to use Photon instead.",
        )
}

/** Directions API. See the class KDoc above before implementing. */
class GoogleDirectionsRoutingService : RoutingService {
    override suspend fun routes(from: GeoPoint, to: Place): List<Route> =
        TODO(
            "Google Directions is not wired up. GET " +
                "maps.googleapis.com/maps/api/directions/json?origin=…&destination=…&alternatives=true&key=… " +
                "then per route: decode overview_polyline.points with Polyline.decode(precision = 5) " +
                "— note precision 5, Valhalla uses 6 — and map each leg.steps[].maneuver string " +
                "(\"turn-left\", \"ramp-right\", \"roundabout-left\", …) onto our Maneuver enum. " +
                "Directions gives no shape indices, so derive shapeStart/shapeEnd by snapping each " +
                "step's start_location onto the decoded shape with RouteMath.snapToRoute. " +
                "Set NavConfig.stack = OSS to use Valhalla instead.",
        )
}
