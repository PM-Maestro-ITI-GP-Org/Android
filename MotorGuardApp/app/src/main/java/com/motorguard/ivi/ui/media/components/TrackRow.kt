package com.motorguard.ivi.ui.media.components

import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.data.media.Track
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.AlbumTheme
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * A row in the queue card: track number (or the equaliser when it is the one playing), title,
 * artist, duration — matching MeowScreen.dc.html.
 */
@Composable
fun TrackRow(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val album = AlbumTheme.colors

    val background by animateColorAsState(
        targetValue = if (isCurrent) album.accent.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = NavMotion.settle(),
        label = "track-row-bg",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                Equalizer(animating = isPlaying, color = album.accent)
            } else {
                Text(
                    text = "${index + 1}",
                    fontSize = 15.sp,
                    color = colors.onBaseDim,
                )
            }
        }

        Spacer(Modifier.width(15.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) album.accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.subtitle.isNotBlank()) {
                Text(
                    text = track.subtitle,
                    fontSize = 12.sp,
                    color = colors.onBaseDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text = formatTime(track.durationMs), fontSize = 12.sp, color = colors.onBaseDim)
    }
}

/**
 * Three bars bouncing on staggered delays — the `@keyframes eq` animation from the design
 * system, in Compose.
 *
 * Scales rather than resizes: `scaleY` on a fixed-height bar is a transform the GPU applies for
 * free, while animating `height` would re-measure the row on every frame. That matters here more
 * than elsewhere, because this animation runs continuously for as long as music is playing.
 */
@Composable
private fun Equalizer(animating: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "equalizer")

    Row(
        modifier = Modifier.height(BAR_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BAR_DELAYS.forEach { delayMs ->
            val scale by transition.animateFloat(
                initialValue = MIN_SCALE,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = CYCLE_MS,
                        delayMillis = 0,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    repeatMode = AnimRepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(delayMs),
                ),
                label = "equalizer-bar",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(BAR_HEIGHT)
                    .graphicsLayer {
                        scaleY = if (animating) scale else MIN_SCALE
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

private val BAR_HEIGHT = 15.dp
private const val CYCLE_MS = 400
private const val MIN_SCALE = 0.3f

/** Staggered starts, so the bars never move as one block. */
private val BAR_DELAYS = listOf(0, 250, 100)
