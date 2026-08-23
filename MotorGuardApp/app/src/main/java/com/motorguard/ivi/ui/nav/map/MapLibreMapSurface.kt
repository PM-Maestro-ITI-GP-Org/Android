package com.motorguard.ivi.ui.nav.map

import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.NavConfig
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The real map: MapLibre Native (BSD-2) rendering OpenFreeMap vector tiles through the
 * token-derived style in [MapStyle].
 *
 * The route is drawn as four stacked line layers over two GeoJSON sources, which is what
 * produces a route that looks designed rather than like a debug polyline:
 *   casing  — wide, near-black, gives the line an edge against pale roads
 *   route   — the accent-coloured body
 *   flow    — accent2 dashes marching towards the destination
 *   passed  — the traveled part, opaque, covering the three layers above
 *
 * Only `passed` changes while driving. `flow` runs the full route and is simply covered by
 * `passed`, which avoids maintaining a second "remaining" polyline that would have to be
 * re-uploaded on every position tick.
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
    onUserPan: () -> Unit = {},
    cameraDriven: Boolean = true,
) {
    val context = LocalContext.current
    val colors = MotorGuard.colors
    // From the theme, not the system: Settings owns Day/Night, and the map is the one surface
    // big enough that disagreeing with the rest of the app is impossible to miss.
    val dark = colors.isDark
    // The surface the whole map palette is blended out of, so it carries the album tint too.
    val mapBase = if (dark) colors.railBg else MaterialTheme.colorScheme.background
    val density = LocalDensity.current.density

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
    // Keyed on the themed colours as well as the mode: when the album (or the Settings accent)
    // shifts the palette, the style is rebuilt and re-applied rather than staying on the hue
    // that happened to be current when the map first loaded.
    DisposableEffect(mapView, dark, mapBase, colors.accent) {
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
            // Distinguish a finger from the code: REASON_API_GESTURE is a pan/zoom by the
            // driver, while our own easeCamera/animateCamera calls report a different reason.
            // Without that distinction, driving the camera would look like a user pan and
            // immediately switch following off.
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    onUserPan()
                }
            }
            ready.setStyle(
                Style.Builder().fromJson(MapStyle.json(dark, mapBase, colors.accent)),
            ) { loaded ->
                loaded.addImage(
                    IMAGE_VEHICLE,
                    VehicleArrow.bitmap(
                        density = density,
                        sizeDp = VehicleArrow.MAP_MARKER_DP,
                        fillColor = colors.accent.toArgb(),
                        outlineColor = if (dark) 0xFF05070A.toInt() else 0xFFFFFFFF.toInt(),
                        haloColor = colors.accent.copy(alpha = 0.20f).toArgb(),
                    ),
                )
                loaded.installRouteLayers(
                    routeColor = colors.accent.toArgb(),
                    flowColor = colors.accent2.toArgb(),
                    casingColor = if (dark) 0xFF05070A.toInt() else 0xFF7A8794.toInt(),
                    passedColor = colors.onBaseDim.toArgb(),
                    destinationColor = colors.accent.toArgb(),
                    destinationRing = colors.highlight.toArgb(),
                    homeColor = colors.accent2.toArgb(),
                    homeRing = if (dark) 0xFF05070A.toInt() else 0xFFFFFFFF.toInt(),
                    labelColor = colors.onBaseDim.toArgb(),
                    labelHalo = if (dark) 0xFF05070A.toInt() else 0xFFFFFFFF.toInt(),
                )
                // The one place-mark that is always on the map, not just while routing:
                // NavConfig.defaultOrigin is ITI, and this is the sign for it.
                loaded.setPoint(SOURCE_HOME, NavConfig.defaultOrigin)
                style = loaded
            }
            map = ready
        }
        onDispose { }
    }

    // --- route geometry: uploaded once per route --------------------------------------------
    //
    // Split from the traveled-portion effect deliberately. These used to share one effect keyed
    // on traveledIndex, which meant the entire polyline — hundreds of points — was re-parsed and
    // re-tiled ten times a second while nothing about it had changed. That churn is visible: the
    // line blinks as it re-tiles.
    LaunchedEffect(style, overlay.route, overlay.flowDashes) {
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
    }

    // --- traveled portion: the only geometry that changes while driving ---------------------
    //
    // Quantized: the shape points are metres apart, so updating every PASSED_STRIDE of them is
    // indistinguishable on screen and cuts the upload rate by the same factor.
    LaunchedEffect(style, overlay.route, overlay.traveledIndex / PASSED_STRIDE) {
        val loaded = style ?: return@LaunchedEffect
        val route = overlay.route
        loaded.setLine(SOURCE_PASSED, route.take((overlay.traveledIndex + 1).coerceAtMost(route.size)))
    }

    // --- endpoint markers -------------------------------------------------------------------
    LaunchedEffect(style, overlay.destination, overlay.origin) {
        val loaded = style ?: return@LaunchedEffect
        loaded.setPoint(SOURCE_DESTINATION, overlay.destination)
        loaded.setPoint(SOURCE_ORIGIN, overlay.origin)
    }

    // --- on-map vehicle marker --------------------------------------------------------------
    LaunchedEffect(style, overlay.vehicle, overlay.vehicleBearingDegrees) {
        val loaded = style ?: return@LaunchedEffect
        loaded.setPoint(SOURCE_VEHICLE, overlay.vehicle)
        // One feature, so the rotation is a layer property rather than a data-driven expression.
        loaded.getLayerAs<SymbolLayer>(LAYER_VEHICLE)
            ?.setProperties(PropertyFactory.iconRotate(overlay.vehicleBearingDegrees))
    }

    // --- camera ---------------------------------------------------------------------------
    LaunchedEffect(map, camera, cameraDriven) {
        // Once the driver has taken the map over, stop steering it. Every position update
        // recomputes `camera`, so without this the map jumps back to the car a fraction of a
        // second after each pan and the map is effectively immovable.
        if (!cameraDriven) return@LaunchedEffect
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
    homeColor: Int,
    homeRing: Int,
    labelColor: Int,
    labelHalo: Int,
) {
    addSource(GeoJsonSource(SOURCE_ROUTE))
    addSource(GeoJsonSource(SOURCE_PASSED))
    addSource(GeoJsonSource(SOURCE_DESTINATION))
    addSource(GeoJsonSource(SOURCE_ORIGIN))
    addSource(GeoJsonSource(SOURCE_VEHICLE))
    addSource(GeoJsonSource(SOURCE_HOME))

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
    // Runs the whole route rather than a separate "remaining" source: the traveled section is
    // covered by LAYER_PASSED above it, which saves a second polyline upload on every tick.
    addLayer(
        LineLayer(LAYER_FLOW, SOURCE_ROUTE).withProperties(
            PropertyFactory.lineColor(flowColor),
            PropertyFactory.lineWidth(FLOW_WIDTH),
            PropertyFactory.lineOpacity(0f),
            PropertyFactory.lineCap("butt"),
        ),
    )
    // Drawn last of the line layers so the traveled section covers the body underneath. Fully
    // opaque on purpose — at partial alpha the animated dashes show through the part already
    // driven, which reads as flicker in exactly the place nothing should be moving.
    addLayer(
        LineLayer(LAYER_PASSED, SOURCE_PASSED).withProperties(
            PropertyFactory.lineColor(passedColor),
            PropertyFactory.lineWidth(ROUTE_WIDTH),
            PropertyFactory.lineOpacity(1f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    // Hollow ring for a custom start point — the same mark the search panel puts next to the
    // origin field, so the two read as the same thing.
    addLayer(
        CircleLayer(LAYER_ORIGIN, SOURCE_ORIGIN).withProperties(
            PropertyFactory.circleColor(casingColor),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(destinationColor),
            PropertyFactory.circleStrokeWidth(4f),
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
    // The ITI sign — always on the map, not gated on a route existing. A slightly larger ring
    // than the destination dot so it reads as a landmark rather than a stop on a trip.
    addLayer(
        CircleLayer(LAYER_HOME, SOURCE_HOME).withProperties(
            PropertyFactory.circleColor(homeColor),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(homeRing),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    addLayer(
        SymbolLayer(LAYER_HOME_LABEL, SOURCE_HOME).withProperties(
            PropertyFactory.textField("ITI"),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textColor(labelColor),
            PropertyFactory.textHaloColor(labelHalo),
            PropertyFactory.textHaloWidth(1.4f),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        ),
    )
    // Topmost, and exempt from collision so it is never dropped in favour of a place label —
    // the one marker on this map that must always be visible.
    addLayer(
        SymbolLayer(LAYER_VEHICLE, SOURCE_VEHICLE).withProperties(
            PropertyFactory.iconImage(IMAGE_VEHICLE),
            PropertyFactory.iconSize(1f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            // Rotate with the map, so the heading stays true when the camera is turned.
            PropertyFactory.iconRotationAlignment("map"),
        ),
    )
}

/** A single-point source, or an empty collection when there is nothing to show. */
private fun Style.setPoint(sourceId: String, point: GeoPoint?) {
    val source = getSourceAs<GeoJsonSource>(sourceId) ?: return
    source.setGeoJson(
        if (point == null) {
            FeatureCollection.fromFeatures(emptyList())
        } else {
            FeatureCollection.fromFeatures(
                listOf(Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat))),
            )
        },
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
private const val SOURCE_DESTINATION = "mg-src-destination"
private const val SOURCE_ORIGIN = "mg-src-origin"
private const val SOURCE_VEHICLE = "mg-src-vehicle"
private const val SOURCE_HOME = "mg-src-home"

private const val LAYER_CASING = "mg-route-casing"
private const val LAYER_ROUTE = "mg-route-line"
private const val LAYER_FLOW = "mg-route-flow"
private const val LAYER_PASSED = "mg-route-passed"
private const val LAYER_DESTINATION = "mg-route-destination"
private const val LAYER_ORIGIN = "mg-route-origin"
private const val LAYER_VEHICLE = "mg-vehicle"
private const val LAYER_HOME = "mg-home"
private const val LAYER_HOME_LABEL = "mg-home-label"
private const val IMAGE_VEHICLE = "mg-vehicle-icon"

private const val CASING_WIDTH = 15f
private const val ROUTE_WIDTH = 9f
private const val FLOW_WIDTH = 9f
private const val FLOW_OPACITY = 0.85f

private const val OVERVIEW_MS = 700
private const val IDLE_ZOOM = 14.5

/** Route draw-in (SHOWCASE): 24 source updates over 720 ms reads as continuous. */
private const val ROUTE_DRAW_STEPS = 24
private const val ROUTE_DRAW_MS = 720L

/** How many shape points the car must advance before the traveled line is re-uploaded. */
private const val PASSED_STRIDE = 4

private const val DASH_LENGTH = 3f
private const val DASH_GAP = 4f
private const val DASH_STEPS = 14

/**
 * One cycle of dash arrays, in line-widths. Walking the list slides the dashes along the route.
 *
 * Generated rather than copied from the Mapbox "animated line" example, because that sequence
 * mixes 3-element and 4-element arrays — and an odd-length dash array repeats twice before the
 * dash/gap roles realign, so its effective period is doubled. The published sequence therefore
 * runs at period 14 for half the cycle and period 7 for the other half: the spacing visibly
 * doubles and snaps back once per cycle, which is exactly what a flicker looks like.
 *
 * Every frame here is `[0, phase, dash, gap - phase]`, so the period stays `dash + gap` while
 * the dash slides forward. At `phase == gap` the dash sits one full period along, which is
 * identical to `phase == 0` — so the wrap is seamless too.
 */
private val DASH_FRAMES: List<Array<Float>> = List(DASH_STEPS) { step ->
    val phase = DASH_GAP * (step + 1) / DASH_STEPS
    arrayOf(0f, phase, DASH_LENGTH, DASH_GAP - phase)
}
