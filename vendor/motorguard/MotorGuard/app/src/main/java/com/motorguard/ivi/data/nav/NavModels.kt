package com.motorguard.ivi.data.nav

/**
 * Provider-agnostic navigation domain.
 *
 * Nothing in here mentions MapLibre, Valhalla, Photon or Google. That is the whole point:
 * the map renderer and the online services sit behind [GeocodingService] / [RoutingService] /
 * [LocationSource], so swapping the OSS stack for a Google one (or for a self-hosted
 * Valhalla) never touches the UI. See docs/08-navigation.md.
 */

/** A WGS84 coordinate. `lat`/`lon` order everywhere in the app; GeoJSON's lon/lat flip
 *  is contained inside the provider implementations. */
data class GeoPoint(val lat: Double, val lon: Double)

/** What kind of thing a search result is — drives the result-row icon only. */
enum class PlaceCategory { CHARGER, FUEL, FOOD, PARKING, SHOP, TRANSPORT, CITY, ADDRESS, OTHER }

/** A search result / destination. */
data class Place(
    val name: String,
    val subtitle: String,
    val point: GeoPoint,
    val category: PlaceCategory = PlaceCategory.OTHER,
)

/**
 * The normalized set of maneuvers we render. Deliberately smaller than any provider's
 * raw enum — each provider maps its own codes onto this, and the UI only ever needs one
 * icon table ([com.motorguard.ivi.ui.nav.components.maneuverIcon]).
 */
enum class Maneuver {
    DEPART,
    CONTINUE,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    UTURN_LEFT,
    UTURN_RIGHT,
    SHARP_RIGHT,
    RIGHT,
    SLIGHT_RIGHT,
    RAMP_LEFT,
    RAMP_RIGHT,
    RAMP_STRAIGHT,
    EXIT_LEFT,
    EXIT_RIGHT,
    MERGE,
    ROUNDABOUT_ENTER,
    ROUNDABOUT_EXIT,
    FERRY,
    ARRIVE,
}

/**
 * One instruction in a route. [shapeStart]/[shapeEnd] index into [Route.shape], which is
 * how we know where along the polyline this step begins and ends without re-matching.
 */
data class RouteStep(
    val maneuver: Maneuver,
    val instruction: String,
    val roadName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val shapeStart: Int,
    val shapeEnd: Int,
)

/**
 * A computed route. [label] is presentation-ready ("fastest route", "+6 min") because only
 * the routing service can compare the alternatives it just produced.
 */
data class Route(
    val id: String,
    val label: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val shape: List<GeoPoint>,
    val steps: List<RouteStep>,
    val destination: Place,
) {
    /** Average speed over the whole route — used by the simulator and by ETA estimates. */
    val averageSpeedMps: Double
        get() = if (durationSeconds > 0) distanceMeters / durationSeconds else 13.9
}

/** Where the car is right now, whoever is reporting it (GNSS or the simulator). */
data class VehiclePosition(
    val point: GeoPoint,
    val bearingDegrees: Float,
    val speedKph: Int,
)

/** Everything the guidance UI needs for one frame, derived from a [Route] + [VehiclePosition]. */
data class NavProgress(
    val currentStep: RouteStep,
    val followingStep: RouteStep?,
    val distanceToManeuverMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    /** 0f at the origin, 1f at the destination — drives the traveled-route dimming. */
    val fractionTraveled: Float,
    /** Index into [Route.shape] the car has reached; the shape before it is "behind us". */
    val traveledShapeIndex: Int,
    val arrived: Boolean,
)
