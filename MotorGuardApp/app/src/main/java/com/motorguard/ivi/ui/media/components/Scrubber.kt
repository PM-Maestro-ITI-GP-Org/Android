package com.motorguard.ivi.ui.media.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.AlbumTheme
import com.motorguard.ivi.ui.theme.MotorGuard
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Elapsed / total time either side of a seekable track, per the design.
 *
 * While dragging, the thumb follows the finger and the player is left alone — committing on
 * release instead of on every pixel avoids a seek storm, and stops the position ticker from
 * yanking the thumb back mid-gesture.
 *
 * @param enabled false for radio and for Bluetooth peers that do not support seeking. Per
 *        docs/04-media.md, scrubbing is also disabled while the vehicle is moving; wire that to
 *        `CarUxRestrictions` here when `CarDataRepository` lands.
 */
@Composable
fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val album = AlbumTheme.colors

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val playbackFraction = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val fraction = if (dragging) dragFraction else playbackFraction
    val animated by animateFloatAsState(
        targetValue = fraction,
        // No easing while dragging: the thumb must track the finger exactly.
        animationSpec = if (dragging) NavMotion.swap() else NavMotion.settle(),
        label = "scrub-progress",
    )

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatTime(if (dragging) (dragFraction * durationMs).roundToLong() else positionMs),
            fontSize = 11.sp,
            color = colors.onBaseDim,
        )
        Spacer(Modifier.width(10.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(THUMB)
                .alpha(if (enabled) 1f else 0.38f),
            contentAlignment = Alignment.CenterStart,
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val thumbHalfPx = with(density) { (THUMB / 2).toPx() }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TRACK_HEIGHT)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.glassBorder)
                    .then(
                        if (!enabled) Modifier else Modifier.pointerInput(widthPx, durationMs) {
                            detectTapGestures { offset ->
                                onSeek(((offset.x / widthPx).coerceIn(0f, 1f) * durationMs).roundToLong())
                            }
                        },
                    ),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TRACK_HEIGHT)
                    .graphicsLayer {
                        scaleX = animated
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .clip(RoundedCornerShape(3.dp))
                    .background(album.accent),
            )

            Box(
                Modifier
                    .offset { IntOffset((animated * widthPx - thumbHalfPx).toInt(), 0) }
                    .size(THUMB)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .then(
                        if (!enabled) Modifier else Modifier.pointerInput(widthPx, durationMs) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragFraction = if (durationMs <= 0) 0f else playbackFraction
                                },
                                onDragEnd = {
                                    dragging = false
                                    onSeek((dragFraction * durationMs).roundToLong())
                                },
                                onDragCancel = { dragging = false },
                            ) { _, delta ->
                                dragFraction = (dragFraction + delta / widthPx).coerceIn(0f, 1f)
                            }
                        },
                    ),
            )
        }

        Spacer(Modifier.width(10.dp))
        Text(text = formatTime(durationMs), fontSize = 11.sp, color = colors.onBaseDim)
    }
}

/** "2:09" / "1:05:23". */
internal fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private val TRACK_HEIGHT = 5.dp
private val THUMB = 14.dp
