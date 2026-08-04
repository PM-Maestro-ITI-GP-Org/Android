package com.motorguard.ivi.ui.nav.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.NavConfig

/**
 * What the map should be showing. Deliberately declarative and provider-free: the Nav screen
 * describes the world, and whichever [MapSurface] implementation is active makes it so.
 */
sealed interface MapCamera {

    /** Chase camera — used while guiding. The car sits at [FOLLOW_ANCHOR_FRACTION] down the view. */
    data class Follow(
        val point: GeoPoint,
        val bearingDegrees: Double,
        val zoom: Double,
        val tiltDegrees: Double,
    ) : MapCamera

    /** Fit these points in view — used for route preview and for the idle map. */
    data class Overview(
        val points: List<GeoPoint>,
        val paddingPx: Int = 96,
    ) : MapCamera
}

/** Geometry drawn on top of the base map. */
data class MapOverlay(
    /** Full route polyline, origin first. Empty means "no route". */
    val route: List<GeoPoint> = emptyList(),
    /** Index into [route] the car has already passed; that part is drawn dimmed. */
    val traveledIndex: Int = 0,
    /** Only set when the trip starts somewhere other than the car — otherwise the puck says it. */
    val origin: GeoPoint? = null,
    val destination: GeoPoint? = null,
    /**
     * The car, drawn *on the map* at its true coordinates.
     *
     * Set this whenever the camera is not a [MapCamera.Follow]. The Compose puck overlay is
     * pinned to a fixed screen position, which is only ever correct when the camera target is
     * the car itself; in any overview the car is somewhere else on screen entirely and has to
     * be geo-anchored like every other piece of geometry.
     */
    val vehicle: GeoPoint? = null,
    val vehicleBearingDegrees: Float = 0f,
    /** Animate dashes flowing towards the destination (guidance only). */
    val flowDashes: Boolean = false,
)

/**
 * Where the follow camera's target lands vertically, as a fraction of view height.
 *
 * Both surfaces and the Compose puck overlay read this single constant, which is what keeps
 * the drawn puck exactly on top of the camera target instead of drifting apart. 0.7 leaves the
 * upper two-thirds of the view for the road ahead.
 */
const val FOLLOW_ANCHOR_FRACTION = 0.7f

/**
 * The map. Picks an implementation from [NavConfig.mapBackend] and — importantly — falls back
 * to the Canvas renderer if MapLibre cannot start.
 *
 * That fallback is not defensive padding: this build targets a Raspberry Pi 5 whose `v3d` GL
 * driver is the least predictable part of the stack, and a nav screen that renders an abstract
 * map is far better than one that renders a black rectangle on demo day.
 */
@Composable
fun MapSurface(
    camera: MapCamera,
    overlay: MapOverlay,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var mapLibreFailed by remember { mutableStateOf(false) }

    // Vector tiles are useless without a network. Checking up front matters because a tile
    // fetch that never completes is silent — MapLibre would happily render an empty world
    // rather than report an error, so there would be nothing to fall back *from*.
    val online = remember { context.hasValidatedInternet() }

    val backend = when {
        mapLibreFailed || !online -> NavConfig.MapBackend.STYLIZED
        else -> NavConfig.mapBackend
    }

    when (backend) {
        NavConfig.MapBackend.MAPLIBRE -> MapLibreMapSurface(
            camera = camera,
            overlay = overlay,
            modifier = modifier,
            onUnavailable = { mapLibreFailed = true },
        )

        NavConfig.MapBackend.STYLIZED -> CanvasMapSurface(
            camera = camera,
            overlay = overlay,
            modifier = modifier,
        )
    }
}

/**
 * True when there is an active network the system has *validated* — i.e. one that actually
 * reaches the internet, not merely an associated Wi-Fi AP. On a bench Pi those two differ often
 * enough to matter.
 */
private fun Context.hasValidatedInternet(): Boolean {
    val manager = getSystemService<ConnectivityManager>() ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
