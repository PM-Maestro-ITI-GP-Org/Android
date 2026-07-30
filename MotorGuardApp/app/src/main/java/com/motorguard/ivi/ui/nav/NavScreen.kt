package com.motorguard.ivi.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.NavConfig
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.nav.components.EtaBar
import com.motorguard.ivi.ui.nav.components.FollowingManeuverChip
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
    val phase = state.phase

    val carPoint = state.position?.point ?: NavConfig.defaultOrigin
    val heading = state.position?.bearingDegrees ?: 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 22.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, colors.glassBorder, RoundedCornerShape(28.dp)),
    ) {
        MapSurface(
            camera = cameraFor(phase, carPoint, heading),
            overlay = overlayFor(phase),
            modifier = Modifier.fillMaxSize(),
        )

        VehicleMarker(phase = phase, heading = heading)

        // One cross-fade between phases, keyed on the phase *type*: guidance updates ten times
        // a second, and keying on the value itself would restart the transition on every tick.
        AnimatedContent(
            targetState = phase,
            contentKey = { it.key },
            transitionSpec = { NavMotion.panelEnter togetherWith NavMotion.panelExit },
            label = "nav-phase",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            Box(Modifier.fillMaxSize()) {
                when (current) {
                    is NavPhase.Idle -> SearchPill(
                        onClick = viewModel::openSearch,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(22.dp),
                    )

                    is NavPhase.Searching -> SearchPanel(
                        query = current.query,
                        results = current.results,
                        loading = current.loading,
                        onQueryChange = viewModel::onQueryChange,
                        onPick = viewModel::pickDestination,
                        onDismiss = viewModel::closeSearch,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(22.dp)
                            .width(SEARCH_PANEL_WIDTH),
                    )

                    is NavPhase.Preview -> RoutePreviewPanel(
                        destination = current.destination,
                        routes = current.routes,
                        selectedIndex = current.selectedIndex,
                        onSelect = viewModel::selectRoute,
                        onStart = viewModel::startGuidance,
                        onCancel = viewModel::cancelPreview,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(22.dp),
                    )

                    is NavPhase.Guiding -> GuidanceOverlay(
                        phase = current,
                        speedKph = state.position?.speedKph ?: 0,
                        onToggleMute = viewModel::toggleMute,
                        onEndRoute = viewModel::endGuidance,
                        onToggleFollow = { viewModel.setFollowing(!current.following) },
                    )
                }
            }
        }

        if (state.routing) {
            RoutingIndicator(modifier = Modifier.align(Alignment.Center))
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

/** Stable identity per phase, so [AnimatedContent] transitions on phase changes only. */
private val NavPhase.key: String
    get() = when (this) {
        is NavPhase.Idle -> "idle"
        is NavPhase.Searching -> "searching"
        is NavPhase.Preview -> "preview"
        is NavPhase.Guiding -> "guiding"
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

        else -> MapCamera.Overview(points = listOf(carPoint), paddingPx = ROUTE_PADDING_PX)
    }

private fun overlayFor(phase: NavPhase): MapOverlay = when (phase) {
    is NavPhase.Guiding -> MapOverlay(
        route = phase.route.shape,
        traveledIndex = phase.progress?.traveledShapeIndex ?: 0,
        destination = phase.route.destination.point,
        flowDashes = true,
    )

    is NavPhase.Preview -> MapOverlay(
        route = phase.selected.shape,
        destination = phase.destination.point,
    )

    else -> MapOverlay()
}

// ---------------------------------------------------------------------------- overlays

/**
 * The car marker, positioned to land exactly on the camera target.
 *
 * While following, the map is rotated to the heading, so the puck points straight up and sits at
 * [FOLLOW_ANCHOR_FRACTION] down the view — matched to the camera padding by sharing that one
 * constant. Otherwise the map is north-up, the puck carries the heading itself, and it belongs
 * dead centre.
 */
@Composable
private fun BoxScope.VehicleMarker(phase: NavPhase, heading: Float) {
    val following = phase is NavPhase.Guiding && phase.following

    if (following) {
        // A column of exactly anchor-fraction height, with the puck hung off its bottom edge and
        // nudged down by half its own size — no measuring pass needed.
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
    } else {
        VehiclePuck(
            rotationDegrees = heading,
            moving = phase is NavPhase.Guiding,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun BoxScope.GuidanceOverlay(
    phase: NavPhase.Guiding,
    speedKph: Int,
    onToggleMute: () -> Unit,
    onEndRoute: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    val progress = phase.progress

    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (progress != null) {
            ManeuverCard(progress = progress)
            FollowingManeuverChip(progress = progress)
        }
    }

    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        SpeedPuck(speedKph = speedKph)
        RecenterButton(following = phase.following, onClick = onToggleFollow)
    }

    EtaBar(
        route = phase.route,
        progress = progress,
        muted = phase.muted,
        onToggleMute = onToggleMute,
        onEndRoute = onEndRoute,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(22.dp),
    )
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

private val SEARCH_PANEL_WIDTH = 420.dp
private val PUCK_HALF = 38.dp
private const val ROUTE_PADDING_PX = 140
