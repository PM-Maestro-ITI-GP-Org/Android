package com.motorguard.ivi.data.nav.oss

import com.motorguard.ivi.data.nav.LocationSource
import com.motorguard.ivi.data.nav.NavConfig
import com.motorguard.ivi.data.nav.Route
import com.motorguard.ivi.data.nav.RouteMath
import com.motorguard.ivi.data.nav.VehiclePosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Drives the car along the active route so the whole feature is demoable at a desk — no GPS
 * receiver, no sky, no moving vehicle. This is the default [LocationSource]; swapping in
 * [AndroidLocationSource] is a one-line change in [com.motorguard.ivi.data.nav.NavProviders].
 *
 * It is not a physics model. It is a believable one: speed eases down towards a maneuver and
 * back up on the straights, and the reported bearing comes from the polyline itself, so the
 * camera behaves the way it will with real positions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedLocationSource : LocationSource {

    private val route = MutableStateFlow<Route?>(null)

    override fun setRoute(route: Route?) {
        this.route.value = route
    }

    override fun positions(): Flow<VehiclePosition> =
        route.asStateFlow().flatMapLatest { active ->
            if (active == null) parked() else drive(active)
        }

    /** No route: the car sits at the origin. One emission, then nothing to report. */
    private fun parked(): Flow<VehiclePosition> = flow {
        emit(VehiclePosition(NavConfig.defaultOrigin, bearingDegrees = 0f, speedKph = 0))
    }

    private fun drive(route: Route): Flow<VehiclePosition> = flow {
        val cumulative = RouteMath.cumulativeDistances(route.shape)
        val total = cumulative.lastOrNull() ?: return@flow
        val cruiseMps = route.averageSpeedMps

        var travelled = 0.0
        var reportedBearing = RouteMath.bearingDegrees(
            route.shape.first(),
            route.shape.getOrElse(1) { route.shape.first() },
        )

        while (travelled < total) {
            val along = RouteMath.pointAlong(route.shape, cumulative, travelled) ?: break
            val speedMps = cruiseMps * speedFactorAt(route, cumulative, travelled)

            // Turn towards the polyline heading instead of snapping — a hard bearing jump
            // makes the camera whip round, which reads as a glitch rather than a turn.
            reportedBearing = (
                reportedBearing + RouteMath.angleDelta(reportedBearing, along.headingDegrees) *
                    BEARING_SMOOTHING
                ).mod(360.0)

            emit(
                VehiclePosition(
                    point = along.point,
                    bearingDegrees = reportedBearing.toFloat(),
                    speedKph = (speedMps * 3.6).roundToInt(),
                ),
            )

            delay(NavConfig.simulationTickMs)
            val tickSeconds = NavConfig.simulationTickMs / 1000.0
            travelled += speedMps * tickSeconds * NavConfig.simulationSpeedFactor
        }

        // Stop cleanly on the destination so the UI can show "arrived" rather than freezing
        // a few metres short.
        emit(
            VehiclePosition(
                point = route.shape.last(),
                bearingDegrees = reportedBearing.toFloat(),
                speedKph = 0,
            ),
        )
    }

    /**
     * Multiplier on cruise speed: slow into maneuvers, pull away from them, and ease off at
     * the very end of the route.
     */
    private fun speedFactorAt(route: Route, cumulative: DoubleArray, travelled: Double): Double {
        val total = cumulative.last()
        val nearestManeuver = route.steps.minOfOrNull { step ->
            abs(cumulative.getOrElse(step.shapeEnd) { total } - travelled)
        } ?: total

        val turnEase = when {
            nearestManeuver > SLOWDOWN_RADIUS_M -> 1.0
            else -> MIN_TURN_FACTOR + (1.0 - MIN_TURN_FACTOR) * (nearestManeuver / SLOWDOWN_RADIUS_M)
        }
        val arrivalEase = min(1.0, max(0.15, (total - travelled) / SLOWDOWN_RADIUS_M))
        return turnEase * arrivalEase
    }

    private companion object {
        /** Fraction of the bearing error corrected per tick — 0.18 at 10 Hz is ~1 s to settle. */
        const val BEARING_SMOOTHING = 0.18

        /** Distance from a maneuver at which the car starts slowing. */
        const val SLOWDOWN_RADIUS_M = 120.0

        /** Slowest the car gets at the apex of a turn, as a fraction of cruise speed. */
        const val MIN_TURN_FACTOR = 0.45
    }
}
