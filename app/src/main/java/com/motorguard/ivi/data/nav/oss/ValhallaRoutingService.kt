package com.motorguard.ivi.data.nav.oss

import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.Maneuver
import com.motorguard.ivi.data.nav.NavConfig
import com.motorguard.ivi.data.nav.NavFormat
import com.motorguard.ivi.data.nav.NavHttp
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.data.nav.Polyline
import com.motorguard.ivi.data.nav.Route
import com.motorguard.ivi.data.nav.RouteStep
import com.motorguard.ivi.data.nav.RoutingService
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Routes from **Valhalla** (OSS, C++ engine, OSM data), via FOSSGIS's public planet server
 * by default. Chosen over OSRM because its maneuver output is richer — typed turns, street
 * names and shape indices per maneuver — which is exactly what the guidance card needs.
 *
 * Wire format notes that cost time to rediscover:
 *  - the request is a JSON blob in the `json` query parameter,
 *  - `shape` is an encoded polyline at **precision 6** (not 5),
 *  - `length` is in the unit named by `directions_options.units` (we ask for kilometres),
 *  - `begin_shape_index` / `end_shape_index` index into that leg's decoded shape.
 */
class ValhallaRoutingService : RoutingService {

    override suspend fun routes(from: GeoPoint, to: Place): List<Route> {
        val request = JSONObject().apply {
            put(
                "locations",
                JSONArray().apply {
                    put(JSONObject().put("lat", from.lat).put("lon", from.lon))
                    put(JSONObject().put("lat", to.point.lat).put("lon", to.point.lon))
                },
            )
            put("costing", "auto")
            put(
                "directions_options",
                JSONObject().put("units", "kilometers").put("language", "en-US"),
            )
            // Ask for two alternates so the preview screen has something to choose between.
            put("alternates", 2)
            put("id", "motorguard")
        }

        val url = "${NavConfig.valhallaBaseUrl}/route?json=" +
            URLEncoder.encode(request.toString(), "UTF-8")
        val response = JSONObject(NavHttp.getString(url))

        val trips = buildList {
            response.optJSONObject("trip")?.let { add(it) }
            response.optJSONArray("alternates")?.let { alternates ->
                for (i in 0 until alternates.length()) {
                    alternates.optJSONObject(i)?.optJSONObject("trip")?.let { add(it) }
                }
            }
        }
        if (trips.isEmpty()) {
            val message = response.optJSONObject("error_code")?.toString()
                ?: response.optString("error").ifBlank { "no route found" }
            throw IllegalStateException("Valhalla: $message")
        }

        val parsed = trips.mapIndexedNotNull { index, trip -> trip.toRoute("valhalla-$index", to) }
        return parsed.labelled()
    }

    /** Labels are relative, so they can only be assigned once every alternative is parsed. */
    private fun List<Route>.labelled(): List<Route> {
        if (isEmpty()) return this
        val sorted = sortedBy { it.durationSeconds }
        val fastest = sorted.first().durationSeconds
        return sorted.mapIndexed { index, route ->
            route.copy(
                label = if (index == 0) {
                    "fastest route"
                } else {
                    NavFormat.durationDelta(route.durationSeconds - fastest)
                },
            )
        }
    }

    private fun JSONObject.toRoute(id: String, destination: Place): Route? {
        val legs = optJSONArray("legs") ?: return null
        val shape = ArrayList<GeoPoint>()
        val steps = ArrayList<RouteStep>()

        for (legIndex in 0 until legs.length()) {
            val leg = legs.optJSONObject(legIndex) ?: continue
            // Each leg's shape indices are leg-local; offset them as we concatenate.
            val offset = shape.size
            val legShape = Polyline.decode(leg.optString("shape"), precision = 6)
            shape.addAll(legShape)

            val maneuvers = leg.optJSONArray("maneuvers") ?: continue
            for (i in 0 until maneuvers.length()) {
                val m = maneuvers.optJSONObject(i) ?: continue
                steps.add(
                    RouteStep(
                        maneuver = maneuverOf(m.optInt("type", 0)),
                        instruction = m.optString("instruction").ifBlank { "Continue" },
                        roadName = m.optJSONArray("street_names")?.joinToText()
                            ?: m.optJSONArray("begin_street_names")?.joinToText()
                            ?: "",
                        distanceMeters = m.optDouble("length", 0.0) * 1000.0,
                        durationSeconds = m.optDouble("time", 0.0),
                        shapeStart = offset + m.optInt("begin_shape_index", 0),
                        shapeEnd = offset + m.optInt("end_shape_index", 0),
                    ),
                )
            }
        }
        if (shape.size < 2) return null

        val summary = optJSONObject("summary") ?: JSONObject()
        return Route(
            id = id,
            label = "",
            distanceMeters = summary.optDouble("length", 0.0) * 1000.0,
            durationSeconds = summary.optDouble("time", 0.0),
            shape = shape,
            steps = steps,
            destination = destination,
        )
    }

    private fun JSONArray.joinToText(): String? {
        val parts = (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
        return parts.joinToString(" / ").ifBlank { null }
    }

    /**
     * Valhalla's `DirectionsLeg.Maneuver.Type` enum, mapped onto our smaller [Maneuver] set.
     * The numbers are stable API and documented in valhalla/proto/directions.proto.
     */
    private fun maneuverOf(type: Int): Maneuver = when (type) {
        1, 2, 3 -> Maneuver.DEPART                    // kStart / kStartRight / kStartLeft
        4, 5, 6 -> Maneuver.ARRIVE                    // kDestination / ...Right / ...Left
        7, 8 -> Maneuver.CONTINUE                     // kBecomes / kContinue
        9 -> Maneuver.SLIGHT_RIGHT
        10 -> Maneuver.RIGHT
        11 -> Maneuver.SHARP_RIGHT
        12 -> Maneuver.UTURN_RIGHT
        13 -> Maneuver.UTURN_LEFT
        14 -> Maneuver.SHARP_LEFT
        15 -> Maneuver.LEFT
        16 -> Maneuver.SLIGHT_LEFT
        17 -> Maneuver.RAMP_STRAIGHT
        18 -> Maneuver.RAMP_RIGHT
        19 -> Maneuver.RAMP_LEFT
        20 -> Maneuver.EXIT_RIGHT
        21 -> Maneuver.EXIT_LEFT
        22 -> Maneuver.CONTINUE                       // kStayStraight
        23 -> Maneuver.SLIGHT_RIGHT                   // kStayRight
        24 -> Maneuver.SLIGHT_LEFT                    // kStayLeft
        25 -> Maneuver.MERGE
        26 -> Maneuver.ROUNDABOUT_ENTER
        27 -> Maneuver.ROUNDABOUT_EXIT
        28, 29 -> Maneuver.FERRY
        else -> Maneuver.CONTINUE
    }
}
