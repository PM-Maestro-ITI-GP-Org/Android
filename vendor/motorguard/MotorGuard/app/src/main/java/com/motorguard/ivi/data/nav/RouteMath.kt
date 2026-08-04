package com.motorguard.ivi.data.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Geodesy + route bookkeeping. Pure functions, no Android and no provider types, so this
 * is the one part of navigation that is trivially unit-testable.
 */
object RouteMath {

    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val DEG = Math.PI / 180.0

    /** Great-circle distance in metres. Haversine is plenty at street scale. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat) * DEG
        val dLon = (b.lon - a.lon) * DEG
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.lat * DEG) * cos(b.lat * DEG) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(s), sqrt(1 - s))
    }

    /** Initial bearing a→b in degrees clockwise from north (0..360). */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.lat * DEG
        val lat2 = b.lat * DEG
        val dLon = (b.lon - a.lon) * DEG
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Linear interpolation between two points. Fine over the few metres between shape points. */
    fun lerp(a: GeoPoint, b: GeoPoint, t: Double): GeoPoint =
        GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /**
     * Shortest angular difference from [from] to [to] in degrees, in (-180, 180].
     * Used to rotate the camera the short way round instead of spinning 350°.
     */
    fun angleDelta(from: Double, to: Double): Double {
        var d = (to - from + 540.0) % 360.0 - 180.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Cumulative distance along a polyline: `out[i]` = metres from shape[0] to shape[i]. */
    fun cumulativeDistances(shape: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(shape.size)
        for (i in 1 until shape.size) {
            out[i] = out[i - 1] + distanceMeters(shape[i - 1], shape[i])
        }
        return out
    }

    /**
     * Point at [meters] along the polyline, plus the index of the shape point just behind it
     * and the heading at that point. Returns null for an empty shape.
     */
    fun pointAlong(shape: List<GeoPoint>, cumulative: DoubleArray, meters: Double): Along? {
        if (shape.isEmpty()) return null
        if (shape.size == 1) return Along(shape[0], 0, 0.0)
        val total = cumulative.last()
        val target = meters.coerceIn(0.0, total)

        // Shape point counts are in the hundreds, so a linear scan is cheaper than the
        // allocation a binary search helper would cost — and it stays readable.
        var i = 1
        while (i < shape.size - 1 && cumulative[i] < target) i++
        val segStart = cumulative[i - 1]
        val segLen = cumulative[i] - segStart
        val t = if (segLen <= 0.0) 0.0 else ((target - segStart) / segLen).coerceIn(0.0, 1.0)
        return Along(
            point = lerp(shape[i - 1], shape[i], t),
            index = i - 1,
            headingDegrees = bearingDegrees(shape[i - 1], shape[i]),
        )
    }

    data class Along(val point: GeoPoint, val index: Int, val headingDegrees: Double)

    /**
     * Snap a free-floating position (real GNSS) onto the route: returns how far along the
     * polyline it is, in metres. Searches the whole shape — routes here are city-scale, and
     * this runs at most a few times a second.
     */
    fun snapToRoute(shape: List<GeoPoint>, cumulative: DoubleArray, position: GeoPoint): Double {
        if (shape.size < 2) return 0.0
        var bestDistance = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 1 until shape.size) {
            val a = shape[i - 1]
            val b = shape[i]
            // Project in a local flat frame — over one segment the error is negligible.
            val scale = cos(a.lat * DEG)
            val ax = 0.0
            val ay = 0.0
            val bx = (b.lon - a.lon) * scale
            val by = (b.lat - a.lat)
            val px = (position.lon - a.lon) * scale
            val py = (position.lat - a.lat)
            val segLenSq = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            val t = if (segLenSq <= 0.0) 0.0 else (((px * bx) + (py * by)) / segLenSq).coerceIn(0.0, 1.0)
            val cx = bx * t
            val cy = by * t
            val d = (px - cx) * (px - cx) + (py - cy) * (py - cy)
            if (d < bestDistance) {
                bestDistance = d
                bestAlong = cumulative[i - 1] + (cumulative[i] - cumulative[i - 1]) * t
            }
        }
        return bestAlong
    }

    /**
     * Turn "how far along the route we are" into everything the guidance overlay shows.
     *
     * Remaining time is scaled from remaining distance rather than summed from the steps:
     * the two agree at the start and it degrades gracefully when the car is between steps.
     */
    fun progress(route: Route, metersAlong: Double, cumulative: DoubleArray): NavProgress {
        val total = if (cumulative.isEmpty()) 0.0 else cumulative.last()
        val along = metersAlong.coerceIn(0.0, total)
        val remaining = total - along

        val stepIndex = route.steps.indexOfLast { step ->
            cumulative.getOrElse(step.shapeStart) { 0.0 } <= along
        }.coerceAtLeast(0)
        val current = route.steps.getOrNull(stepIndex)
            ?: RouteStep(Maneuver.ARRIVE, "Arrive", route.destination.name, 0.0, 0.0, 0, 0)
        val following = route.steps.getOrNull(stepIndex + 1)

        // The maneuver happens at the END of the current step.
        val maneuverAt = cumulative.getOrElse(current.shapeEnd) { total }
        val toManeuver = max(0.0, maneuverAt - along)

        val traveledIndex = shapeIndexAt(cumulative, along)
        return NavProgress(
            currentStep = current,
            followingStep = following,
            distanceToManeuverMeters = toManeuver,
            remainingDistanceMeters = remaining,
            remainingDurationSeconds = if (total <= 0) 0.0 else route.durationSeconds * (remaining / total),
            fractionTraveled = if (total <= 0) 0f else (along / total).toFloat(),
            traveledShapeIndex = traveledIndex,
            arrived = remaining < ARRIVAL_RADIUS_M,
        )
    }

    /** Index of the last shape point at or before [metersAlong]. */
    fun shapeIndexAt(cumulative: DoubleArray, metersAlong: Double): Int {
        if (cumulative.isEmpty()) return 0
        var i = 0
        while (i < cumulative.size - 1 && cumulative[i + 1] <= metersAlong) i++
        return i
    }

    /** Bounding box of a set of points, as (south-west, north-east). */
    fun bounds(points: List<GeoPoint>): Pair<GeoPoint, GeoPoint>? {
        if (points.isEmpty()) return null
        var minLat = points[0].lat
        var maxLat = points[0].lat
        var minLon = points[0].lon
        var maxLon = points[0].lon
        for (p in points) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        return GeoPoint(minLat, minLon) to GeoPoint(maxLat, maxLon)
    }

    /**
     * Zoom level at which [points] fit inside a [widthPx] x [heightPx] viewport, allowing
     * [paddingPx] on every side. Web Mercator, 512 px tiles — same convention as MapLibre.
     */
    fun zoomForBounds(points: List<GeoPoint>, widthPx: Int, heightPx: Int, paddingPx: Int): Double {
        val (sw, ne) = bounds(points) ?: return 12.0
        val usableW = max(64, widthPx - paddingPx * 2)
        val usableH = max(64, heightPx - paddingPx * 2)
        val lonSpan = max(1e-6, abs(ne.lon - sw.lon)) / 360.0
        val latSpan = max(1e-6, abs(mercatorY(ne.lat) - mercatorY(sw.lat)))
        val zx = ln(usableW / (TILE_SIZE * lonSpan)) / ln(2.0)
        val zy = ln(usableH / (TILE_SIZE * latSpan)) / ln(2.0)
        return min(zx, zy).coerceIn(2.0, 17.0)
    }

    /** Normalized Web Mercator Y in 0..1 (0 = north pole side). */
    fun mercatorY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        return 0.5 - ln(tan(Math.PI / 4 + clamped * DEG / 2)) / (2 * Math.PI)
    }

    /** Normalized Web Mercator X in 0..1. */
    fun mercatorX(lon: Double): Double = (lon + 180.0) / 360.0

    const val TILE_SIZE = 512.0

    /** Treat the destination as reached inside this radius. */
    const val ARRIVAL_RADIUS_M = 25.0
}

/**
 * Google's encoded-polyline algorithm. Valhalla emits precision 6; OSRM and Google use 5,
 * so the precision is a parameter and any of them can be decoded here.
 */
object Polyline {

    fun decode(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            out.add(GeoPoint(lat / factor, lon / factor))
        }
        return out
    }
}
