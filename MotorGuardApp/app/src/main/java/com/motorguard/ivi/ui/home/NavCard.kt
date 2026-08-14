package com.motorguard.ivi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.data.nav.NavFormat
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.nav.NavPhase
import com.motorguard.ivi.ui.nav.NavSession
import com.motorguard.ivi.data.nav.NavConfig
import com.motorguard.ivi.ui.nav.map.MapCamera
import com.motorguard.ivi.ui.nav.map.MapOverlay
import com.motorguard.ivi.ui.nav.map.MapSurface
import com.motorguard.ivi.ui.theme.MotorGuard

/** Frames the route with a little air around it inside a card this small. */
private const val MINI_MAP_PADDING_PX = 48

/**
 * The Home mini-map, per docs/03-home.md: glanceable map, ETA while a trip is running, and a
 * tap that opens the Nav tab.
 *
 * It reads [NavSession] — the same process-lifetime session the Nav tab drives — rather than
 * owning any navigation state of its own. That is what makes a trip started on the Nav tab
 * already visible here, still advancing, instead of this card showing a second, idle map.
 *
 * The camera is always an overview, never the chase camera Nav uses. A heading-up map that
 * rotates under the car needs room to read; at this size the useful question is "where am I
 * and how far is left", which an overview answers at a glance.
 *
 * @param onOpenNav tapping the card jumps to the Nav tab.
 */
@Composable
fun NavCard(onOpenNav: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Idempotent: if the driver has already opened Nav this is a no-op, and if they have not,
    // it starts the position feed so the card shows the car rather than an empty world.
    LaunchedEffect(Unit) { NavSession.ensureStarted(context.applicationContext) }

    val state by NavSession.state.collectAsStateWithLifecycle()
    val phase = state.phase
    val car = state.position?.point
    val guiding = phase as? NavPhase.Guiding

    // What is left of the trip — the part already driven is not a decision the driver still has
    // to make, so it should not claim half the card.
    val shape = when (phase) {
        is NavPhase.Guiding -> phase.route.shape
            .drop(phase.progress?.traveledShapeIndex ?: 0)
            .ifEmpty { phase.route.shape }
        is NavPhase.Preview -> phase.selected.shape
        else -> emptyList()
    }
    // Fall back to the same origin the Nav tab uses when there is no fix, so Home shows a map
    // from the first frame instead of a "waiting for position" notice. A first fix can take
    // tens of seconds outdoors and may never arrive indoors, which made the card look broken
    // for most of a demo; a map of roughly the right place is honest enough for a card whose
    // whole job is to be tapped.
    val framed = shape.ifEmpty { listOf(car ?: NavConfig.defaultOrigin) }

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        padding = PaddingValues(0.dp),
        soft = true,
    ) {
        MapSurface(
                camera = MapCamera.Overview(points = framed, paddingPx = MINI_MAP_PADDING_PX),
            overlay = MapOverlay(
                route = shape,
                destination = when (phase) {
                    is NavPhase.Guiding -> phase.route.destination.point
                    is NavPhase.Preview -> phase.destination.point
                    else -> null
                },
                // Always geo-anchored: the camera here is never Follow, so the car has to be
                // drawn at its true coordinates rather than pinned to a screen position.
                vehicle = car,
                vehicleBearingDegrees = state.position?.bearingDegrees ?: 0f,
            ),
            modifier = Modifier.fillMaxSize(),
        )

        // The tap target sits *above* the map, not on the card behind it: MapLibre's MapView
        // consumes touches for its own pan/zoom, so a clickable underneath would never fire.
        // Swallowing those gestures is the intent anyway — a glanceable Home widget that can be
        // dragged out of position, with no way to recentre it, is a trap rather than a feature.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onOpenNav),
        )

        TripStrip(
            title = guiding?.route?.destination?.name ?: "Navigation",
            detail = guiding?.progress?.let { progress ->
                "${NavFormat.distance(progress.remainingDistanceMeters)} · " +
                    "arrive ${NavFormat.arrivalTime(progress.remainingDurationSeconds)}"
            } ?: "Tap to set a destination",
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * Destination + remaining distance and arrival time, over a scrim.
 *
 * The scrim is not decoration: this sits on live map tiles whose brightness is outside our
 * control, and white-on-anything is only legible with something behind it.
 */
@Composable
private fun TripStrip(title: String, detail: String, modifier: Modifier = Modifier) {
    val colors = MotorGuard.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.glass)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = detail, fontSize = 13.sp, color = colors.onBaseDim, maxLines = 1)
    }
}
