package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.data.nav.NavFormat
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.data.nav.Route
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * Route preview: the destination, the alternatives Valhalla returned, and the one control that
 * matters — Start.
 *
 * Alternatives are rows rather than a carousel because comparing them is the whole point, and
 * a driver should be able to read all three at a glance instead of swiping through them.
 */
@Composable
fun RoutePreviewPanel(
    origin: Place?,
    destination: Place,
    routes: List<Route>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        padding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        // The one panel that earns a repeating shimmer: it is transient, it is the moment the
        // driver is deciding, and it is never on screen while the car is moving.
        shimmer = NavMotion.glassShimmer,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(colors.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // The whole endpoint block is the way back into the search panel, so
                        // fixing a wrong start does not mean cancelling and starting over.
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onEdit)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "from ${origin?.name ?: "Your location"}",
                        fontSize = 13.sp,
                        color = colors.onBaseDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = destination.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (destination.subtitle.isNotBlank()) {
                        Text(
                            text = destination.subtitle,
                            fontSize = 13.sp,
                            color = colors.onBaseDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.chip)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel",
                        tint = colors.onBaseDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                routes.forEachIndexed { index, route ->
                    RouteOption(
                        route = route,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            StartButton(onClick = onStart)
        }
    }
}

@Composable
private fun RouteOption(
    route: Route,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val border by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.glassBorder,
        animationSpec = NavMotion.settle(),
        label = "route-option-border",
    )
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accent.copy(alpha = 0.14f) else colors.chip,
        animationSpec = NavMotion.settle(),
        label = "route-option-fill",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.AltRoute,
                contentDescription = null,
                tint = if (selected) colors.accent else colors.onBaseDim,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = route.label,
                fontSize = 12.sp,
                color = if (selected) colors.accent else colors.onBaseDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = NavFormat.duration(route.durationSeconds),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${NavFormat.distance(route.distanceMeters)} · arrive ${NavFormat.arrivalTime(route.durationSeconds)}",
            fontSize = 12.sp,
            color = colors.onBaseDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The commit control. Full width and 76 dp tall because it is the one button on this screen a
 * driver may reach for while moving — the docs' minimum touch target, not a suggestion.
 */
@Composable
private fun StartButton(onClick: () -> Unit) {
    val colors = MotorGuard.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = NavMotion.swap(),
        label = "start-press",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.accent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.NearMe,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = "Start",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}
