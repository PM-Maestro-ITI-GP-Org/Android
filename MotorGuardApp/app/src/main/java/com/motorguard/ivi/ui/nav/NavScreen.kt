package com.motorguard.ivi.ui.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.NavConfig
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.nav.components.EtaBar
import com.motorguard.ivi.ui.nav.components.FollowingManeuverChip
import com.motorguard.ivi.ui.nav.components.LocationStatusChip
import com.motorguard.ivi.ui.nav.components.ManeuverCard
import com.motorguard.ivi.ui.nav.components.RecenterButton
import com.motorguard.ivi.ui.nav.components.RoutePreviewPanel
import com.motorguard.ivi.ui.nav.components.SearchPanel
import com.motorguard.ivi.ui.nav.components.SearchPill
import com.motorguard.ivi.ui.nav.components.SpeedPuck
import com.motorguard.ivi.ui.nav.components.VehiclePuck
import com.motorguard.ivi.ui.nav.map.FOLLOW_ANCHOR_FRACTION
import com.motorguard.ivi.ui.nav.map.MapCamera
import com.motorguard.ivi.ui.nav.map.MapOverlay
import com.motorguard.ivi.ui.nav.map.MapSurface
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The Navigation surface: one full-bleed map card with glass controls floating over it, laid
 * out to match the nav screen in MeowScreen.dc.html.
 *
 * The map is a single instance for the whole tab and is never torn down between phases — search,
 * preview and guidance are all overlays on top of it. That is what lets the camera *fly* from
 * "here is the city" to "here is your route" to "here is your next turn" instead of cutting, and
 * it is the difference between one instrument and four screens stapled together.
 */
@Composable
fun NavScreen(viewModel: NavViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MotorGuard.colors
    val context = LocalContext.current
    val phase = state.phase

    val carPoint = state.position?.point ?: NavConfig.defaultOrigin
    val heading = state.position?.bearingDegrees ?: 0f

    // --- runtime location permission ------------------------------------------------------
    var permissionGranted by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        permissionGranted = granted.values.any { it }
        viewModel.onLocationPermissionResult(permissionGranted)
    }
    val requestPermission = { permissionLauncher.launch(LOCATION_PERMISSIONS) }

    // Ask once, on entering the tab, and only when GNSS is the source that needs it.
    LaunchedEffect(Unit) {
        if (NavConfig.locationMode == NavConfig.LocationMode.GNSS && !permissionGranted) {
            requestPermission()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 22.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, colors.glassBorder, RoundedCornerShape(28.dp)),
    ) {
        val camera = cameraFor(phase, carPoint, heading)

        // Has the driver taken the map over by dragging it?
        //
        // `camera` is recomputed from the car's position, so it changes on every GPS tick.
        // Applying it unconditionally meant a pan was undone within a second and the map could
        // not be explored at all. Panning now suspends the camera until it is recentred.
        // Reset when the phase changes: choosing a destination or starting guidance is a fresh
        // intent, and the map should frame that rather than stay where it was left.
        var panned by remember(phase::class) { mutableStateOf(false) }

        // The car is drawn on the map unless the camera is locked to it. The Compose puck is
        // pinned to a fixed screen position, so it is only ever truthful in Follow mode — in any
        // overview the car sits wherever its coordinates say, which is not the middle.
        val following = camera is MapCamera.Follow

        MapSurface(
            camera = camera,
            overlay = overlayFor(
                phase = phase,
                vehicle = if (following) null else state.position?.point,
                vehicleBearing = heading,
            ),
            modifier = Modifier.fillMaxSize(),
            onUserPan = { panned = true },
            cameraDriven = !panned,
        )

        // Only truthful while the camera is actually locked to the car — after a pan the car is
        // wherever its coordinates put it, not under a puck pinned to the middle of the screen.
        if (following && !panned) {
            FollowVehicleMarker()
        }

        // Somewhere to go back to. A map that can be dragged with no way to recentre is worse
        // than one that cannot be dragged at all, so this appears exactly when it is needed.
        androidx.compose.animation.AnimatedVisibility(
            visible = panned,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp),
        ) {
            RecenterButton(following = false, onClick = { panned = false })
        }

        // One AnimatedVisibility per phase, driven by a *boolean*.
        //
        // This used to be a single AnimatedContent keyed on the phase type, which was wrong:
        // AnimatedContent re-runs its transition whenever targetState changes by value, and
        // contentKey only reuses the composition slot — it does not suppress the animation. So
        // every keystroke produced a new Searching value and replayed the enter transition,
        // over a full-screen container whose height made the slide enormous. A boolean only
        // flips when the phase actually changes, and each panel now slides relative to its own
        // height instead of the screen's.
        AnimatedVisibility(
            visible = phase is NavPhase.Idle,
            enter = NavMotion.panelEnter,
            exit = NavMotion.panelExit,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(22.dp),
        ) {
            SearchPill(onClick = viewModel::openSearch)
        }

        // Panels keep their last value so an exit animation still has something to draw after
        // the phase has already moved on.
        val searching = rememberLast(phase as? NavPhase.Searching)
        AnimatedVisibility(
            visible = phase is NavPhase.Searching,
            enter = NavMotion.panelEnter,
            exit = NavMotion.panelExit,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(22.dp)
                .width(SEARCH_PANEL_WIDTH),
        ) {
            searching?.let { current ->
                SearchPanel(
                    origin = current.origin,
                    destination = current.destination,
                    active = current.active,
                    query = current.query,
                    results = current.results,
                    loading = current.loading,
                    canUseCurrentLocation = state.position != null,
                    onQueryChange = viewModel::onQueryChange,
                    onActivate = viewModel::activateField,
                    onPick = viewModel::pickResult,
                    onUseCurrentLocation = viewModel::useCurrentLocationAsOrigin,
                    onSwap = viewModel::swapEndpoints,
                    onDismiss = viewModel::closeSearch,
                )
            }
        }

        val preview = rememberLast(phase as? NavPhase.Preview)
        AnimatedVisibility(
            visible = phase is NavPhase.Preview,
            enter = NavMotion.panelEnter,
            exit = NavMotion.panelExit,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(22.dp),
        ) {
            preview?.let { current ->
                RoutePreviewPanel(
                    origin = current.origin,
                    destination = current.destination,
                    routes = current.routes,
                    selectedIndex = current.selectedIndex,
                    onSelect = viewModel::selectRoute,
                    onStart = viewModel::startGuidance,
                    onEdit = viewModel::editRoute,
                    onCancel = viewModel::cancelPreview,
                )
            }
        }

        val guiding = rememberLast(phase as? NavPhase.Guiding)
        GuidanceOverlay(
            visible = phase is NavPhase.Guiding,
            phase = guiding,
            speedKph = state.position?.speedKph ?: 0,
            onToggleMute = viewModel::toggleMute,
            onEndRoute = viewModel::endGuidance,
            onToggleFollow = { guiding?.let { viewModel.setFollowing(!it.following) } },
        )

        if (state.routing) {
            RoutingIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // No fix yet: say so, and offer the way out. Sits at the bottom-centre only while the
        // ETA bar is not there to claim that space.
        AnimatedVisibility(
            visible = NavConfig.locationMode == NavConfig.LocationMode.GNSS &&
                state.position == null,
            enter = NavMotion.panelEnter,
            exit = NavMotion.panelExit,
            modifier = Modifier
                .align(if (phase is NavPhase.Guiding) Alignment.Center else Alignment.BottomCenter)
                .padding(22.dp),
        ) {
            LocationStatusChip(
                permissionGranted = permissionGranted,
                onGrantPermission = requestPermission,
            )
        }

        ErrorBanner(
            message = state.error,
            onDismiss = viewModel::dismissError,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 22.dp),
        )

        Attribution(modifier = Modifier.align(Alignment.BottomStart))
    }
}

/**
 * Holds on to the most recent non-null [value].
 *
 * A panel's exit animation outlives the phase that produced it: by the time the search panel is
 * sliding away, `phase` is already `Idle` and the cast to `Searching` is null. Without this the
 * panel would blank out and then animate an empty box. The holder is a plain list rather than
 * snapshot state on purpose — it must not itself trigger a recomposition.
 */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    val holder = remember { mutableListOf<T>() }
    if (value != null) {
        holder.clear()
        holder.add(value)
    }
    return value ?: holder.firstOrNull()
}

// ---------------------------------------------------------------------------- camera & overlay

private fun cameraFor(phase: NavPhase, carPoint: GeoPoint, heading: Float): MapCamera =
    when (phase) {
        is NavPhase.Guiding -> if (phase.following) {
            // Heading-up: the map rotates under a car that always points forward. This is the
            // orientation drivers read fastest, because "left on screen" means "left in front
            // of you".
            MapCamera.Follow(
                point = carPoint,
                bearingDegrees = heading.toDouble(),
                zoom = NavMotion.followZoom,
                tiltDegrees = NavMotion.cameraTiltDegrees,
            )
        } else {
            // Frame what is *left* of the trip. The part already driven is not a decision the
            // driver still has to make.
            val remaining = phase.route.shape.drop(phase.progress?.traveledShapeIndex ?: 0)
            MapCamera.Overview(
                points = if (remaining.size >= 2) remaining else phase.route.shape,
                paddingPx = ROUTE_PADDING_PX,
            )
        }

        is NavPhase.Preview -> MapCamera.Overview(
            points = phase.selected.shape,
            paddingPx = ROUTE_PADDING_PX,
        )

        // While searching, frame whichever endpoints are already chosen — picking a start
        // somewhere else should take the map there, not leave it sitting on the car.
        is NavPhase.Searching -> MapCamera.Overview(
            points = listOfNotNull(phase.origin?.point, phase.destination?.point)
                .ifEmpty { listOf(carPoint) },
            paddingPx = ROUTE_PADDING_PX,
        )

        else -> MapCamera.Overview(points = listOf(carPoint), paddingPx = ROUTE_PADDING_PX)
    }

/**
 * @param vehicle where to draw the car on the map, or null when the camera is following it and
 *        the Compose puck has that job instead.
 */
private fun overlayFor(
    phase: NavPhase,
    vehicle: GeoPoint?,
    vehicleBearing: Float,
): MapOverlay {
    val base = when (phase) {
        is NavPhase.Guiding -> MapOverlay(
            route = phase.route.shape,
            traveledIndex = phase.progress?.traveledShapeIndex ?: 0,
            destination = phase.route.destination.point,
            flowDashes = true,
        )

        is NavPhase.Preview -> MapOverlay(
            route = phase.selected.shape,
            origin = phase.origin?.point,
            destination = phase.destination.point,
        )

        is NavPhase.Searching -> MapOverlay(
            origin = phase.origin?.point,
            destination = phase.destination?.point,
        )

        else -> MapOverlay()
    }
    return base.copy(vehicle = vehicle, vehicleBearingDegrees = vehicleBearing)
}

// ---------------------------------------------------------------------------- overlays

/**
 * The car marker for the chase camera only.
 *
 * Here — and only here — the car genuinely is at a fixed screen position: the camera follows it,
 * so the world slides underneath while the puck stays put. That makes it free to animate, and it
 * points straight up because the map itself is rotated to the heading.
 *
 * It sits at [FOLLOW_ANCHOR_FRACTION] down the view, the same constant the camera padding is
 * derived from — which is what keeps the drawn puck exactly on the camera target.
 */
@Composable
private fun BoxScope.FollowVehicleMarker() {
    // A box of exactly anchor-fraction height, with the puck hung off its bottom edge and nudged
    // down by half its own size — no measuring pass needed.
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxHeight(FOLLOW_ANCHOR_FRACTION),
        contentAlignment = Alignment.BottomCenter,
    ) {
        VehiclePuck(
            rotationDegrees = 0f,
            moving = true,
            modifier = Modifier.offset(y = PUCK_HALF),
        )
    }
}

/**
 * Guidance chrome, in three independently-animated corners.
 *
 * Each corner gets its own [AnimatedVisibility] so it slides relative to its own height, not the
 * screen's — and, critically, so the ten-times-a-second progress updates flow straight through
 * to the cards without touching any transition.
 */
@Composable
private fun BoxScope.GuidanceOverlay(
    visible: Boolean,
    phase: NavPhase.Guiding?,
    speedKph: Int,
    onToggleMute: () -> Unit,
    onEndRoute: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    val progress = phase?.progress

    AnimatedVisibility(
        visible = visible,
        enter = NavMotion.panelEnter,
        exit = NavMotion.panelExit,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (progress != null) {
                ManeuverCard(progress = progress)
                FollowingManeuverChip(progress = progress)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = NavMotion.panelEnter,
        exit = NavMotion.panelExit,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(22.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SpeedPuck(speedKph = speedKph)
            RecenterButton(following = phase?.following ?: true, onClick = onToggleFollow)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = NavMotion.panelEnter,
        exit = NavMotion.panelExit,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(22.dp),
    ) {
        if (phase != null) {
            EtaBar(
                route = phase.route,
                progress = progress,
                muted = phase.muted,
                onToggleMute = onToggleMute,
                onEndRoute = onEndRoute,
            )
        }
    }
}

// ---------------------------------------------------------------------------- small pieces

@Composable
private fun RoutingIndicator(modifier: Modifier = Modifier) {
    val colors = MotorGuard.colors
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        padding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = "Finding routes…",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MotorGuard.colors
    AnimatedVisibility(
        visible = message != null,
        enter = NavMotion.panelEnter,
        exit = NavMotion.panelExit,
        modifier = modifier,
    ) {
        GlassCard(
            shape = RoundedCornerShape(18.dp),
            padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.critical),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = message.orEmpty(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    // Endpoint diagnostics are longer than "no connection"; wrap rather than
                    // stretch the banner across the whole map.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
                Spacer(Modifier.width(16.dp))
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = colors.onBaseDim,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onDismiss),
                )
            }
        }
    }
}

/** ODbL requires visible credit for OSM-derived tiles, routes and search results. */
@Composable
private fun Attribution(modifier: Modifier = Modifier) {
    Text(
        text = NavConfig.ATTRIBUTION,
        fontSize = 10.sp,
        color = MotorGuard.colors.onBaseDim,
        modifier = modifier.padding(start = 14.dp, bottom = 6.dp),
    )
}

/** Coarse is requested alongside fine so a "approximate location only" grant still works. */
private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun Context.hasLocationPermission(): Boolean = LOCATION_PERMISSIONS.any {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private val SEARCH_PANEL_WIDTH = 420.dp
private val PUCK_HALF = 38.dp
private const val ROUTE_PADDING_PX = 140
