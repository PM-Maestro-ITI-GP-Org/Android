package com.motorguard.ivi.ui.nav.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The real map: MapLibre Native (BSD-2) rendering OpenFreeMap vector tiles through the
 * token-derived style in [MapStyle].
 *
 * The route is drawn as four stacked line layers on three GeoJSON sources, which is what
 * produces a route that looks designed rather than like a debug polyline:
 *   casing  — wide, near-black, gives the line an edge against pale roads
 *   route   — the accent-coloured body
 *   flow    — accent2 dashes marching towards the destination, on the *remaining* geometry only
 *   passed  — the traveled part, greyed back over the body
 *
 * @param onUnavailable called when MapLibre cannot start at all (missing/limping GL driver on
 *        the Pi throws an `UnsatisfiedLinkError` from the native loader, which is an `Error`,
 *        not an `Exception` — hence `runCatching`). [MapSurface] then swaps in the Canvas
 *        renderer for the rest of the session.
 */
@Composable
internal fun MapLibreMapSurface(
    camera: MapCamera,
    overlay: MapOverlay,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = MotorGuard.colors

    // MapLibre.getInstance() must run before any MapView is constructed.
    val mapView: MapView? = remember {
        runCatching {
            MapLibre.getInstance(context)
            MapView(context).apply { onCreate(null) }
        }.getOrNull()
    }

    if (mapView == null) {
        LaunchedEffect(Unit) { onUnavailable() }
        return
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    // --- lifecycle: MapView is a View with its own onStart/onStop contract -----------------
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // --- style + route layers -------------------------------------------------------------
    DisposableEffect(mapView, dark) {
        mapView.getMapAsync { ready ->
            ready.uiSettings.apply {
                // No MapLibre chrome: the design system owns every control on this screen.
                isLogoEnabled = false
                isAttributionEnabled = false
                isCompassEnabled = false
                // Rotate/tilt are driven by guidance, not by fingers, and a stray two-finger
                // twist while driving is exactly the kind of thing CarUxRestrictions exists for.
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
            }
            ready.setStyle(Style.Builder().fromJson(MapStyle.json(dark))) { loaded ->
                loaded.installRouteLayers(
                    routeColor = colors.accent.toArgb(),
                    flowColor = colors.accent2.toArgb(),
                    casingColor = if (dark) 0xFF05070A.toInt() else 0xFF7A8794.toInt(),
                    passedColor = colors.onBaseDim.toArgb(),
                    destinationColor = colors.accent.toArgb(),
                    destinationRing = colors.highlight.toArgb(),
                )
                style = loaded
            }
            map = ready
        }
        onDispose { }
    }

    // --- overlay geometry -----------------------------------------------------------------
    LaunchedEffect(style, overlay.route, overlay.traveledIndex, overlay.destination) {
        val loaded = style ?: return@LaunchedEffect
        val route = overlay.route

        // SHOWCASE only: sweep the line out from the origin when a route first appears, so the
        // preview reads as "here is the way there" rather than a shape that was always present.
        // Skipped while guiding — a driver needs the route now, not in 700 ms.
        if (NavMotion.routeDrawIn && !overlay.flowDashes && route.size >= 2) {
            repeat(ROUTE_DRAW_STEPS) { step ->
                val count = (route.size * (step + 1) / ROUTE_DRAW_STEPS).coerceAtLeast(2)
                loaded.setLine(SOURCE_ROUTE, route.take(count))
                delay(ROUTE_DRAW_MS / ROUTE_DRAW_STEPS)
            }
        }

        loaded.setLine(SOURCE_ROUTE, route)
        loaded.setLine(SOURCE_PASSED, route.take((overlay.traveledIndex + 1).coerceAtMost(route.size)))
        loaded.setLine(SOURCE_REMAINING, route.drop(overlay.traveledIndex))
        loaded.getSourceAs<GeoJsonSource>(SOURCE_DESTINATION)?.setGeoJson(
            overlay.destination?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }
                ?.let { FeatureCollection.fromFeatures(listOf(it)) }
                ?: FeatureCollection.fromFeatures(emptyList()),
        )
    }

    // --- camera ---------------------------------------------------------------------------
    LaunchedEffect(map, camera) {
        val active = map ?: return@LaunchedEffect
        when (camera) {
            is MapCamera.Follow -> {
                // Push the camera target down the viewport so the road ahead gets the space.
                // Deriving the padding from FOLLOW_ANCHOR_FRACTION is what keeps the Compose
                // puck sitting exactly on the target: target lands at (top + (h - top) / 2).
                val topPadding = mapView.height * (2.0 * FOLLOW_ANCHOR_FRACTION - 1.0)
                val position = CameraPosition.Builder()
                    .target(LatLng(camera.point.lat, camera.point.lon))
                    .zoom(camera.zoom)
                    .bearing(camera.bearingDegrees)
                    .tilt(camera.tiltDegrees)
                    .padding(0.0, topPadding.coerceAtLeast(0.0), 0.0, 0.0)
                    .build()
                val update = CameraUpdateFactory.newCameraPosition(position)
                if (NavMotion.cameraEaseMs > 0) {
                    active.easeCamera(update, NavMotion.cameraEaseMs)
                } else {
                    active.moveCamera(update)
                }
            }

            is MapCamera.Overview -> {
                val points = camera.points
                when {
                    points.size >= 2 -> {
                        val bounds = LatLngBounds.Builder()
                            .includes(points.map { LatLng(it.lat, it.lon) })
                            .build()
                        active.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(bounds, camera.paddingPx),
                            OVERVIEW_MS,
                        )
                    }

                    points.size == 1 -> active.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(points[0].lat, points[0].lon),
                            IDLE_ZOOM,
                        ),
                        OVERVIEW_MS,
                    )

                    else -> Unit
                }
            }
        }
    }

    // --- flowing dashes -------------------------------------------------------------------
    // MapLibre has no dash-offset property, so the classic trick is to cycle the dash array
    // itself. One style-property write per frame at ~17 fps: far cheaper than it looks, and it
    // is the only way to get marching ants out of the GL renderer.
    LaunchedEffect(style, overlay.flowDashes, NavMotion.routeDashFlow) {
        val loaded = style ?: return@LaunchedEffect
        val layer = loaded.getLayerAs<LineLayer>(LAYER_FLOW) ?: return@LaunchedEffect
        if (!overlay.flowDashes || !NavMotion.routeDashFlow) {
            layer.setProperties(PropertyFactory.lineOpacity(0f))
            return@LaunchedEffect
        }
        layer.setProperties(PropertyFactory.lineOpacity(FLOW_OPACITY))
        var frame = 0
        while (true) {
            layer.setProperties(PropertyFactory.lineDasharray(DASH_FRAMES[frame % DASH_FRAMES.size]))
            frame++
            delay(NavMotion.DASH_FRAME_MS)
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Adds the route sources + layers on top of the styled basemap. Idempotent per style load. */
private fun Style.installRouteLayers(
    routeColor: Int,
    flowColor: Int,
    casingColor: Int,
    passedColor: Int,
    destinationColor: Int,
    destinationRing: Int,
) {
    addSource(GeoJsonSource(SOURCE_ROUTE))
    addSource(GeoJsonSource(SOURCE_PASSED))
    addSource(GeoJsonSource(SOURCE_REMAINING))
    addSource(GeoJsonSource(SOURCE_DESTINATION))

    addLayer(
        LineLayer(LAYER_CASING, SOURCE_ROUTE).withProperties(
            PropertyFactory.lineColor(casingColor),
            PropertyFactory.lineWidth(CASING_WIDTH),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    addLayer(
        LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
            PropertyFactory.lineColor(routeColor),
            PropertyFactory.lineWidth(ROUTE_WIDTH),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    addLayer(
        LineLayer(LAYER_FLOW, SOURCE_REMAINING).withProperties(
            PropertyFactory.lineColor(flowColor),
            PropertyFactory.lineWidth(FLOW_WIDTH),
            PropertyFactory.lineOpacity(0f),
            PropertyFactory.lineCap("butt"),
        ),
    )
    // Drawn last of the line layers so the traveled section greys back the body underneath.
    addLayer(
        LineLayer(LAYER_PASSED, SOURCE_PASSED).withProperties(
            PropertyFactory.lineColor(passedColor),
            PropertyFactory.lineWidth(ROUTE_WIDTH),
            PropertyFactory.lineOpacity(PASSED_OPACITY),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    addLayer(
        CircleLayer(LAYER_DESTINATION, SOURCE_DESTINATION).withProperties(
            PropertyFactory.circleColor(destinationColor),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor(destinationRing),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

/** GeoJSON is lon/lat. A line needs two points; anything less becomes an empty collection. */
private fun Style.setLine(sourceId: String, points: List<GeoPoint>) {
    val source = getSourceAs<GeoJsonSource>(sourceId) ?: return
    if (points.size < 2) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    source.setGeoJson(
        LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) }),
    )
}

private const val SOURCE_ROUTE = "mg-src-route"
private const val SOURCE_PASSED = "mg-src-passed"
private const val SOURCE_REMAINING = "mg-src-remaining"
private const val SOURCE_DESTINATION = "mg-src-destination"

private const val LAYER_CASING = "mg-route-casing"
private const val LAYER_ROUTE = "mg-route-line"
private const val LAYER_FLOW = "mg-route-flow"
private const val LAYER_PASSED = "mg-route-passed"
private const val LAYER_DESTINATION = "mg-route-destination"

private const val CASING_WIDTH = 15f
private const val ROUTE_WIDTH = 9f
private const val FLOW_WIDTH = 9f
private const val FLOW_OPACITY = 0.85f
private const val PASSED_OPACITY = 0.75f

private const val OVERVIEW_MS = 700
private const val IDLE_ZOOM = 14.5

/** Route draw-in (SHOWCASE): 24 source updates over 720 ms reads as continuous. */
private const val ROUTE_DRAW_STEPS = 24
private const val ROUTE_DRAW_MS = 720L

/**
 * One cycle of dash arrays. Values are in line-widths; walking the list makes the gaps appear
 * to travel along the line. Same sequence the Mapbox/MapLibre "animated line" example uses.
 */
private val DASH_FRAMES: List<Array<Float>> = listOf(
    arrayOf(0f, 4f, 3f),
    arrayOf(0.5f, 4f, 2.5f),
    arrayOf(1f, 4f, 2f),
    arrayOf(1.5f, 4f, 1.5f),
    arrayOf(2f, 4f, 1f),
    arrayOf(2.5f, 4f, 0.5f),
    arrayOf(3f, 4f, 0f),
    arrayOf(0f, 0.5f, 3f, 3.5f),
    arrayOf(0f, 1f, 3f, 3f),
    arrayOf(0f, 1.5f, 3f, 2.5f),
    arrayOf(0f, 2f, 3f, 2f),
    arrayOf(0f, 2.5f, 3f, 1.5f),
    arrayOf(0f, 3f, 3f, 1f),
    arrayOf(0f, 3.5f, 3f, 0.5f),
)
