package com.motorguard.ivi.data.nav.oss

import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.GeocodingService
import com.motorguard.ivi.data.nav.NavConfig
import com.motorguard.ivi.data.nav.NavHttp
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.data.nav.PlaceCategory
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Destination search against **Photon** (komoot, OSS, OSM data).
 *
 * Photon is chosen over Nominatim on purpose: Nominatim's usage policy caps you at 1 req/s
 * and explicitly disallows autocomplete, which is exactly what a search box does. Photon is
 * built for search-as-you-type. The public instance is still fair-use — self-host
 * (`NavConfig.photonBaseUrl`) before this ships in a car.
 *
 * Response is a GeoJSON FeatureCollection; note GeoJSON is **lon, lat**.
 */
class PhotonGeocodingService : GeocodingService {

    override suspend fun search(query: String, near: GeoPoint?): List<Place> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY) return emptyList()

        val url = buildString {
            append(NavConfig.photonBaseUrl)
            append("/api/?q=")
            append(URLEncoder.encode(trimmed, "UTF-8"))
            append("&limit=").append(RESULT_LIMIT)
            append("&lang=en")
            if (near != null) {
                // Bias towards the car so "market" means the one down the road.
                append("&lat=").append(near.lat)
                append("&lon=").append(near.lon)
            }
        }

        val features = JSONObject(NavHttp.getString(url)).optJSONArray("features") ?: return emptyList()
        val places = ArrayList<Place>(features.length())
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            places.add(feature.toPlace() ?: continue)
        }
        return places
    }

    /**
     * Photon's `/reverse`, which is the endpoint that actually answers "what fuel is near me".
     *
     * `/api` is a text index: `lat`/`lon` bias the ranking of a global name match, which is why
     * asking it for "petrol station" returned places called that hours away and ranked them above
     * the one down the road — the road one is not called "Petrol Station", it is called Wataniya
     * and tagged `amenity=fuel`. `/reverse` queries by position with an `osm_tag` filter and
     * returns nearest first, which is the question that was being asked all along.
     *
     * `radius` is a hard limit rather than a preference, which is the point: nothing in range is
     * a real and useful answer, where a text search always has something to offer and no way to
     * say how far away it is.
     */
    override suspend fun nearby(osmTags: List<String>, near: GeoPoint, radiusKm: Int): List<Place> {
        if (osmTags.isEmpty()) return emptyList()
        val url = buildString {
            append(NavConfig.photonBaseUrl)
            append("/reverse?lat=").append(near.lat)
            append("&lon=").append(near.lon)
            append("&radius=").append(radiusKm)
            append("&limit=").append(NEARBY_LIMIT)
            append("&lang=en")
            // Repeated osm_tag is OR in Photon, which is what a "car centre" needs: repair shops,
            // dealers and tyre places are three tags and one errand.
            osmTags.forEach { append("&osm_tag=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val features = JSONObject(NavHttp.getString(url)).optJSONArray("features") ?: return emptyList()
        val places = ArrayList<Place>(features.length())
        for (i in 0 until features.length()) {
            places.add(features.optJSONObject(i)?.toPlace() ?: continue)
        }
        return places
    }

    private fun JSONObject.toPlace(): Place? {
        val coordinates = optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        if (coordinates.length() < 2) return null
        val point = GeoPoint(lat = coordinates.getDouble(1), lon = coordinates.getDouble(0))
        val props = optJSONObject("properties") ?: JSONObject()

        val street = props.optNullableString("street")
        val houseNumber = props.optNullableString("housenumber")
        val streetLine = listOfNotNull(street, houseNumber).joinToString(" ").ifBlank { null }

        // Unnamed features (a plain address) fall back to the street line, then the city.
        val name = props.optNullableString("name")
            ?: streetLine
            ?: props.optNullableString("city")
            ?: return null

        val subtitle = listOfNotNull(
            streetLine.takeIf { it != name },
            props.optNullableString("district"),
            props.optNullableString("city"),
            props.optNullableString("state"),
            props.optNullableString("country"),
        ).distinct().joinToString(" · ")

        return Place(
            name = name,
            subtitle = subtitle,
            point = point,
            category = categoryOf(props.optNullableString("osm_key"), props.optNullableString("osm_value")),
        )
    }

    /** OSM key/value → the small icon set the result rows use. */
    private fun categoryOf(key: String?, value: String?): PlaceCategory = when {
        value == "charging_station" -> PlaceCategory.CHARGER
        value == "fuel" -> PlaceCategory.FUEL
        value == "parking" || value == "parking_entrance" -> PlaceCategory.PARKING
        value in setOf("restaurant", "cafe", "fast_food", "bar", "pub", "food_court") -> PlaceCategory.FOOD
        key == "shop" || value == "supermarket" || value == "mall" -> PlaceCategory.SHOP
        key in setOf("railway", "aeroway", "public_transport") || value == "bus_station" -> PlaceCategory.TRANSPORT
        key == "place" && value in setOf("city", "town", "village", "suburb", "neighbourhood") -> PlaceCategory.CITY
        key == "highway" || key == "building" -> PlaceCategory.ADDRESS
        else -> PlaceCategory.OTHER
    }

    /** `optString` returns "" for a missing key, which then defeats every `?:` below. */
    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() }

    private companion object {
        const val MIN_QUERY = 2
        const val RESULT_LIMIT = 8

        /** Enough that the road-distance ranking has real choice without asking much of a
         *  public instance. */
        const val NEARBY_LIMIT = 12
    }
}
