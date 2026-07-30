package com.motorguard.ivi.ui.nav.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.RouteMath
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard
import com.motorguard.ivi.ui.theme.Tokens
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The offline map: an abstract street grid drawn straight onto a Canvas, with the **real**
 * route geometry projected on top in true Web Mercator.
 *
 * This exists because a nav screen that cannot draw anything is worse than one that draws
 * something honest. It runs with no GL, no tiles and no network, which covers three real
 * situations: the Pi's `v3d` driver misbehaving, a bench with no Wi-Fi, and Compose previews.
 * The grid is deliberately abstract rather than fake-realistic — it reads as a schematic, not
 * as wrong data. Everything geographic on screen (route, destination, heading) is exact.
 */
@Composable
internal fun CanvasMapSurface(
    camera: MapCamera,
    overlay: MapOverlay,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val colors = MotorGuard.colors
    val density = LocalDensity.current
    val palette = remember(dark) { canvasPalette(dark) }

    var viewSize by remember { mutableStateOf(Offset.Zero) }

    // Resolve the declarative camera into concrete target/zoom/bearing for this viewport.
    val requested = resolveCamera(camera, viewSize)
    val latest by rememberUpdatedState(requested)
    var current by remember { mutableStateOf(requested) }

    // Exponential smoothing towards the requested camera, per frame. MapLibre gets this from
    // easeCamera; here it is six lines, and it is what stops a 10 Hz position feed from
    // looking like a stop-motion animation.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            current = current.approach(latest, CAMERA_SMOOTHING)
        }
    }

    val dashPhase = if (overlay.flowDashes && NavMotion.routeDashFlow) rememberDashPhase() else 0f

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Captured in the layout phase, never in the draw block: writing snapshot state
            // while drawing invalidates the frame that is being drawn, which is how you get a
            // recomposition loop that only shows up as a mysterious frame-rate cliff.
            .onSizeChanged { viewSize = Offset(it.width.toFloat(), it.height.toFloat()) },
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        drawRect(palette.background)

        val worldSize = RouteMath.TILE_SIZE * 2.0.pow(current.zoom)
        val targetX = (RouteMath.mercatorX(current.target.lon) * worldSize).toFloat()
        val targetY = (RouteMath.mercatorY(current.target.lat) * worldSize).toFloat()
        val anchor = Offset(size.width / 2f, size.height * current.anchorFraction)
        // Everything on screen must be reachable within this radius of the camera target.
        val reach = sqrt(size.width * size.width + size.height * size.height)

        withTransform({
            translate(anchor.x, anchor.y)
            rotate(-current.bearing.toFloat(), pivot = Offset.Zero)
            translate(-targetX, -targetY)
        }) {
            drawStreetGrid(palette, targetX, targetY, reach, density.density)
            drawRoute(overlay, palette, worldSize, dashPhase, density.density)
        }
    }
}

// ---------------------------------------------------------------------------- camera

/** A camera the Canvas renderer can actually draw with. */
private data class CanvasCamera(
    val target: GeoPoint,
    val zoom: Double,
    val bearing: Double,
    val anchorFraction: Float,
) {
    /** Move [fraction] of the way towards [other], taking the short way round on bearing. */
    fun approach(other: CanvasCamera, fraction: Float): CanvasCamera = CanvasCamera(
        target = RouteMath.lerp(target, other.target, fraction.toDouble()),
        zoom = zoom + (other.zoom - zoom) * fraction,
        bearing = (bearing + RouteMath.angleDelta(bearing, other.bearing) * fraction).mod(360.0),
        anchorFraction = anchorFraction + (other.anchorFraction - anchorFraction) * fraction,
    )
}

private fun resolveCamera(camera: MapCamera, viewSize: Offset): CanvasCamera = when (camera) {
    is MapCamera.Follow -> CanvasCamera(
        target = camera.point,
        zoom = camera.zoom,
        bearing = camera.bearingDegrees,
        anchorFraction = FOLLOW_ANCHOR_FRACTION,
    )

    is MapCamera.Overview -> {
        val points = camera.points
        val bounds = RouteMath.bounds(points)
        val centre = if (bounds == null) {
            com.motorguard.ivi.data.nav.NavConfig.defaultOrigin
        } else {
            GeoPoint((bounds.first.lat + bounds.second.lat) / 2, (bounds.first.lon + bounds.second.lon) / 2)
        }
        val width = viewSize.x.toInt().takeIf { it > 0 } ?: 1280
        val height = viewSize.y.toInt().takeIf { it > 0 } ?: 720
        CanvasCamera(
            target = centre,
            zoom = if (points.size >= 2) {
                RouteMath.zoomForBounds(points, width, height, camera.paddingPx)
            } else {
                IDLE_ZOOM
            },
            bearing = 0.0,
            anchorFraction = 0.5f,
        )
    }
}

@Composable
private fun rememberDashPhase(): Float {
    val transition = rememberInfiniteTransition(label = "canvas-route-dash")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = DASH_CYCLE_PX,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "canvas-route-dash-phase",
    )
    return phase
}

// ---------------------------------------------------------------------------- drawing

private class CanvasPalette(
    val background: Color,
    val block: Color,
    val street: Color,
    val avenue: Color,
    val routeCasing: Color,
    val route: Color,
    val routeFlow: Color,
    val routePassed: Color,
    val destination: Color,
    val destinationRing: Color,
)

private fun canvasPalette(dark: Boolean): CanvasPalette = if (dark) {
    val base = Tokens.Night.railBg
    CanvasPalette(
        background = base,
        block = lerp(base, Tokens.Night.panel, 0.55f),
        street = lerp(base, Tokens.Night.onBaseDim, 0.16f),
        avenue = lerp(base, Tokens.Night.onBaseDim, 0.30f),
        routeCasing = Color(0xFF05070A),
        route = Tokens.Night.accent,
        routeFlow = Tokens.Night.accent2,
        routePassed = lerp(base, Tokens.Night.onBaseDim, 0.45f),
        destination = Tokens.Night.accent,
        destinationRing = Color(0x33FFFFFF),
    )
} else {
    val base = Tokens.Day.base
    CanvasPalette(
        background = base,
        block = lerp(base, Tokens.Day.onBaseDim, 0.10f),
        street = Color.White,
        avenue = Color.White,
        routeCasing = lerp(Tokens.Day.accent, Color.Black, 0.35f),
        route = Tokens.Day.accent,
        routeFlow = Tokens.Day.accent2,
        routePassed = lerp(base, Tokens.Day.onBaseDim, 0.35f),
        destination = Tokens.Day.accent,
        destinationRing = Color.White,
    )
}

/**
 * A periodic street grid in *world* pixels. Because the pattern repeats every [BLOCK_PX] and is
 * snapped to that period, it is stable as the camera moves: the grid slides past the car exactly
 * like a real one would, with no popping or crawling.
 */
private fun DrawScope.drawStreetGrid(
    palette: CanvasPalette,
    targetX: Float,
    targetY: Float,
    reach: Float,
    density: Float,
) {
    val block = BLOCK_PX * density
    val startX = (floor((targetX - reach) / block) * block)
    val endX = targetX + reach
    val startY = (floor((targetY - reach) / block) * block)
    val endY = targetY + reach

    // City blocks first, so the streets read as gaps between them rather than lines on nothing.
    var bx = startX
    while (bx < endX) {
        var by = startY
        while (by < endY) {
            drawRect(
                color = palette.block,
                topLeft = Offset(bx + block * 0.08f, by + block * 0.08f),
                size = androidx.compose.ui.geometry.Size(block * 0.84f, block * 0.84f),
            )
            by += block
        }
        bx += block
    }

    val streetWidth = 3f * density
    val avenueWidth = 7f * density
    var x = startX
    var index = (startX / block).toInt()
    while (x < endX) {
        val avenue = index.mod(AVENUE_EVERY) == 0
        drawLine(
            color = if (avenue) palette.avenue else palette.street,
            start = Offset(x, startY),
            end = Offset(x, endY),
            strokeWidth = if (avenue) avenueWidth else streetWidth,
        )
        x += block
        index++
    }
    var y = startY
    index = (startY / block).toInt()
    while (y < endY) {
        val avenue = index.mod(AVENUE_EVERY) == 0
        drawLine(
            color = if (avenue) palette.avenue else palette.street,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = if (avenue) avenueWidth else streetWidth,
        )
        y += block
        index++
    }

    // A couple of diagonals break up the regularity. Positions are snapped to a coarse
    // multiple of the block size, so they too stay put in world space.
    val diagonalSpacing = block * DIAGONAL_EVERY
    val snappedX = floor(targetX / diagonalSpacing) * diagonalSpacing
    val snappedY = floor(targetY / diagonalSpacing) * diagonalSpacing
    val direction = Offset(cos(DIAGONAL_RADIANS).toFloat(), sin(DIAGONAL_RADIANS).toFloat())
    for (k in -1..1) {
        val origin = Offset(snappedX + k * diagonalSpacing, snappedY)
        drawLine(
            color = palette.avenue,
            start = origin - direction * reach,
            end = origin + direction * reach,
            strokeWidth = avenueWidth,
        )
    }
}

private fun DrawScope.drawRoute(
    overlay: MapOverlay,
    palette: CanvasPalette,
    worldSize: Double,
    dashPhase: Float,
    density: Float,
) {
    val route = overlay.route
    if (route.size >= 2) {
        val casingWidth = 15f * density
        val lineWidth = 9f * density

        val full = route.toWorldPath(worldSize)
        drawPath(full, palette.routeCasing, style = Stroke(casingWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(full, palette.route, style = Stroke(lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

        if (dashPhase != 0f) {
            val remaining = route.drop(overlay.traveledIndex)
            if (remaining.size >= 2) {
                drawPath(
                    path = remaining.toWorldPath(worldSize),
                    color = palette.routeFlow,
                    alpha = 0.85f,
                    style = Stroke(
                        width = lineWidth,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(DASH_ON_PX * density, DASH_OFF_PX * density),
                            -dashPhase * density,
                        ),
                    ),
                )
            }
        }

        val passed = route.take((overlay.traveledIndex + 1).coerceAtMost(route.size))
        if (passed.size >= 2) {
            drawPath(
                path = passed.toWorldPath(worldSize),
                color = palette.routePassed,
                alpha = 0.85f,
                style = Stroke(lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }

    overlay.origin?.let { origin ->
        val point = origin.toWorld(worldSize)
        drawCircle(
            color = palette.destination,
            radius = 9f * density,
            center = point,
            style = Stroke(width = 4f * density),
        )
    }

    overlay.destination?.let { destination ->
        val point = destination.toWorld(worldSize)
        drawCircle(palette.destinationRing, radius = 13f * density, center = point)
        drawCircle(palette.destination, radius = 9f * density, center = point)
    }

    // The car, at its true coordinates. Drawn inside the world transform, so rotating it by the
    // vehicle bearing alone is correct — the camera's own rotation is already applied.
    overlay.vehicle?.let { vehicle ->
        val centre = vehicle.toWorld(worldSize)
        val radius = VehicleArrow.MAP_MARKER_DP * density / 2f
        drawCircle(palette.route, radius = radius * 0.44f, center = centre, alpha = 0.20f)
        rotate(degrees = overlay.vehicleBearingDegrees, pivot = centre) {
            val arrow = vehicleArrowPath(centre, radius * VehicleArrow.ARROW_SCALE)
            drawPath(
                path = arrow,
                color = palette.routeCasing,
                style = Stroke(width = radius * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(path = arrow, color = palette.route)
        }
    }
}

private fun vehicleArrowPath(centre: Offset, size: Float): Path = Path().apply {
    VehicleArrow.outline.forEachIndexed { index, (x, y) ->
        val px = centre.x + x * size
        val py = centre.y + y * size
        if (index == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

private fun GeoPoint.toWorld(worldSize: Double) = Offset(
    (RouteMath.mercatorX(lon) * worldSize).toFloat(),
    (RouteMath.mercatorY(lat) * worldSize).toFloat(),
)

private fun List<GeoPoint>.toWorldPath(worldSize: Double): Path = Path().apply {
    forEachIndexed { index, point ->
        val offset = point.toWorld(worldSize)
        if (index == 0) moveTo(offset.x, offset.y) else lineTo(offset.x, offset.y)
    }
}

private const val IDLE_ZOOM = 14.5

/** Fraction of the remaining camera error corrected per frame (~0.18 ≈ 200 ms to settle at 60 fps). */
private const val CAMERA_SMOOTHING = 0.18f

private const val BLOCK_PX = 78f
private const val AVENUE_EVERY = 4
private const val DIAGONAL_EVERY = 9f
private val DIAGONAL_RADIANS = Math.toRadians(34.0)

private const val DASH_ON_PX = 16f
private const val DASH_OFF_PX = 20f
private const val DASH_CYCLE_PX = DASH_ON_PX + DASH_OFF_PX
