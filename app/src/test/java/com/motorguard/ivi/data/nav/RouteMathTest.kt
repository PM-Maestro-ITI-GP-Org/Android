package com.motorguard.ivi.data.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation maths is the part that has to be right before anything can be seen on screen:
 * a wrong polyline precision or an off-by-one in the step lookup produces a map that renders
 * beautifully and guides you into a wall.
 */
class RouteMathTest {

    @Test
    fun `haversine matches a known city distance`() {
        // Cairo → Giza, roughly 12.6 km apart.
        val cairo = GeoPoint(30.0444, 31.2357)
        val giza = GeoPoint(30.0131, 31.2089)
        val metres = RouteMath.distanceMeters(cairo, giza)
        assertTrue("expected ~4 km, got $metres", metres in 3_500.0..5_000.0)
    }

    @Test
    fun `bearing due east is 90 degrees`() {
        val from = GeoPoint(0.0, 0.0)
        val to = GeoPoint(0.0, 1.0)
        assertEquals(90.0, RouteMath.bearingDegrees(from, to), 0.5)
    }

    @Test
    fun `angleDelta takes the short way round`() {
        assertEquals(20.0, RouteMath.angleDelta(350.0, 10.0), 0.001)
        assertEquals(-20.0, RouteMath.angleDelta(10.0, 350.0), 0.001)
    }

    @Test
    fun `pointAlong walks the polyline`() {
        val shape = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        val cumulative = RouteMath.cumulativeDistances(shape)
        val half = RouteMath.pointAlong(shape, cumulative, cumulative.last() / 2)
        assertNotNull(half)
        assertEquals(0.5, half!!.point.lon, 0.01)
    }

    @Test
    fun `snapToRoute finds the nearest position along an off-route fix`() {
        val shape = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        val cumulative = RouteMath.cumulativeDistances(shape)
        // A fix sitting a little north of the halfway point — like real GNSS noise.
        val along = RouteMath.snapToRoute(shape, cumulative, GeoPoint(0.001, 0.5))
        assertEquals(cumulative.last() / 2, along, cumulative.last() * 0.02)
    }

    @Test
    fun `progress reports the current step and distance to its maneuver`() {
        val shape = (0..10).map { GeoPoint(0.0, it * 0.01) }
        val cumulative = RouteMath.cumulativeDistances(shape)
        val route = Route(
            id = "t",
            label = "fastest route",
            distanceMeters = cumulative.last(),
            durationSeconds = 600.0,
            shape = shape,
            steps = listOf(
                RouteStep(Maneuver.DEPART, "Head east", "A", 0.0, 0.0, 0, 5),
                RouteStep(Maneuver.RIGHT, "Turn right", "B", 0.0, 0.0, 5, 10),
            ),
            destination = Place("End", "", shape.last()),
        )

        val quarter = RouteMath.progress(route, cumulative.last() * 0.25, cumulative)
        assertEquals(Maneuver.DEPART, quarter.currentStep.maneuver)
        assertEquals(Maneuver.RIGHT, quarter.followingStep?.maneuver)
        // The maneuver is at the END of the current step, i.e. halfway along this route.
        assertEquals(cumulative.last() * 0.25, quarter.distanceToManeuverMeters, cumulative.last() * 0.02)
        assertEquals(0.25f, quarter.fractionTraveled, 0.01f)
        assertTrue(!quarter.arrived)

        val end = RouteMath.progress(route, cumulative.last(), cumulative)
        assertTrue("should report arrival at the destination", end.arrived)
    }

    @Test
    fun `polyline decodes the reference precision-5 fixture`() {
        // The canonical example from Google's encoded-polyline documentation.
        val decoded = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, decoded.size)
        assertEquals(38.5, decoded[0].lat, 1e-5)
        assertEquals(-120.2, decoded[0].lon, 1e-5)
        assertEquals(40.7, decoded[1].lat, 1e-5)
        assertEquals(-120.95, decoded[1].lon, 1e-5)
        assertEquals(43.252, decoded[2].lat, 1e-5)
        assertEquals(-126.453, decoded[2].lon, 1e-5)
    }

    @Test
    fun `polyline precision 6 is ten times finer than precision 5`() {
        // Valhalla emits precision 6; decoding it as 5 silently yields coordinates ten times
        // too large. This test is here so that mistake can never come back quietly.
        val encoded = "_p~iF~ps|U"
        val five = Polyline.decode(encoded, precision = 5).first()
        val six = Polyline.decode(encoded, precision = 6).first()
        assertEquals(five.lat, six.lat * 10, 1e-6)
    }

    @Test
    fun `zoomForBounds keeps a route inside the viewport`() {
        val points = listOf(GeoPoint(30.0, 31.0), GeoPoint(30.1, 31.1))
        val zoom = RouteMath.zoomForBounds(points, widthPx = 1600, heightPx = 640, paddingPx = 140)
        assertTrue("zoom out of range: $zoom", zoom in 2.0..17.0)

        // A tighter box must be viewable from closer in.
        val tighter = listOf(GeoPoint(30.0, 31.0), GeoPoint(30.001, 31.001))
        assertTrue(RouteMath.zoomForBounds(tighter, 1600, 640, 140) > zoom)
    }
}
