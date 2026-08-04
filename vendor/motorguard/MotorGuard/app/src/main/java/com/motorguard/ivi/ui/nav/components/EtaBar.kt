package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.data.nav.NavFormat
import com.motorguard.ivi.data.nav.NavProgress
import com.motorguard.ivi.data.nav.Route
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.components.GlassChip
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The trip bar along the bottom of the map while guiding: arrival clock, remaining time and
 * distance, then the voice-mute and end-route controls.
 *
 * A hairline progress rail runs along the bottom edge of the card. It is the one piece of
 * information the design system's static mock could not show — it turns "62.3 km" into a sense
 * of how far through the trip you are, and it costs one `drawRect`.
 */
@Composable
fun EtaBar(
    route: Route,
    progress: NavProgress?,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onEndRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val remainingSeconds = progress?.remainingDurationSeconds ?: route.durationSeconds
    val remainingMeters = progress?.remainingDistanceMeters ?: route.distanceMeters

    val fraction by animateFloatAsState(
        targetValue = progress?.fractionTraveled ?: 0f,
        animationSpec = NavMotion.settle(),
        label = "trip-progress",
    )

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        padding = PaddingValues(start = 26.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stat(
                    value = NavFormat.arrivalTime(remainingSeconds),
                    label = "Arrival",
                    countsDown = false,
                )
                Spacer(Modifier.width(24.dp))
                Box(
                    Modifier
                        .width(1.dp)
                        .height(38.dp)
                        .background(colors.glassBorder),
                )
                Spacer(Modifier.width(24.dp))
                Stat(
                    value = NavFormat.duration(remainingSeconds),
                    label = "${NavFormat.distance(remainingMeters)} · ${route.label}",
                )

                Spacer(Modifier.weight(1f))

                GlassChip(
                    size = 52.dp,
                    modifier = Modifier.clickable(onClick = onToggleMute),
                ) {
                    Icon(
                        imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (muted) "Unmute guidance" else "Mute guidance",
                        tint = if (muted) colors.onBaseDim else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                GlassChip(
                    size = 52.dp,
                    fill = colors.critical,
                    borderColor = Color.Transparent,
                    modifier = Modifier.clickable(onClick = onEndRoute),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "End route",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            TripProgressRail(fraction = fraction)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, countsDown: Boolean = true) {
    val colors = MotorGuard.colors
    Column {
        RollingValue(
            value = value,
            countsDown = countsDown,
            style = TextStyle(
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Spacer(Modifier.height(2.dp))
        Text(text = label, fontSize = 12.sp, color = colors.onBaseDim)
    }
}

/**
 * Track plus accent fill. The fill is scaled with `graphicsLayer` rather than resized with
 * `fillMaxWidth(fraction)`: scaling is a transform the GPU applies for free, resizing would
 * re-measure and re-layout on every frame of the animation. That is the README's perf rule.
 */
@Composable
private fun TripProgressRail(fraction: Float) {
    val colors = MotorGuard.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.glassBorder),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .graphicsLayer {
                    scaleX = fraction.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clip(RoundedCornerShape(2.dp))
                .background(colors.accent),
        )
    }
}
