package com.motorguard.ivi.ui.media.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.media.RepeatMode
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.AlbumTheme
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The transport row from the design: shuffle · previous · a 66 dp filled play/pause · next ·
 * repeat.
 *
 * Shuffle and repeat take the album accent when active, which is where the artwork theme is most
 * legible — a coloured state on an otherwise monochrome row.
 */
@Composable
fun TransportBar(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    enabled: Boolean,
    canSkip: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val album = AlbumTheme.colors

    Row(
        modifier = modifier,
        // The mockup spaces these glyphs 26 px apart. Wrapping each in a 76 dp touch target —
        // the figure docs/README.md requires — already separates them, so the explicit gap goes
        // to zero. The row ends up a little wider than the mock; the touch-target rule wins.
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(
            icon = Icons.Filled.Shuffle,
            description = "Shuffle",
            size = 24.dp,
            tint = if (shuffle) album.accent else colors.onBaseDim,
            enabled = enabled,
            onClick = onShuffle,
        )
        TransportIcon(
            icon = Icons.Filled.SkipPrevious,
            description = "Previous track",
            size = 30.dp,
            tint = MaterialTheme.colorScheme.onSurface,
            enabled = enabled && canSkip,
            onClick = onPrevious,
        )
        PlayPauseButton(isPlaying = isPlaying, enabled = enabled, onClick = onPlayPause)
        TransportIcon(
            icon = Icons.Filled.SkipNext,
            description = "Next track",
            size = 30.dp,
            tint = MaterialTheme.colorScheme.onSurface,
            enabled = enabled && canSkip,
            onClick = onNext,
        )
        TransportIcon(
            icon = if (repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            description = "Repeat",
            size = 24.dp,
            tint = if (repeat == RepeatMode.OFF) colors.onBaseDim else album.accent,
            enabled = enabled,
            onClick = onRepeat,
        )
    }
}

/**
 * The primary control. Filled with the foreground colour and punched through with the base
 * colour, exactly as the mockup has it — this is the one element on the screen that inverts.
 */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    diameter: Dp = 66.dp,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = NavMotion.swap(),
        label = "play-press",
    )

    Box(
        modifier = modifier
            .size(diameter)
            .scale(scale)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Cross-scale rather than a hard swap: this icon changes on every tap, and it is the
        // clearest confirmation the press registered.
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (scaleIn(NavMotion.swap(), initialScale = 0.6f) + fadeIn(NavMotion.swap()))
                    .togetherWith(scaleOut(NavMotion.swap(), targetScale = 0.6f) + fadeOut(NavMotion.swap()))
            },
            label = "play-pause-icon",
        ) { playing ->
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(diameter * 0.48f),
            )
        }
    }
}

@Composable
private fun TransportIcon(
    icon: ImageVector,
    description: String,
    size: Dp,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = NavMotion.swap(),
        label = "transport-press",
    )

    Box(
        // The glyph is 24–30 dp; the hit area is the docs' 76 dp minimum. Visual size and touch
        // size are not the same thing in a moving vehicle.
        modifier = Modifier
            .size(TOUCH_TARGET)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier
                .size(size)
                .scale(scale)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
        )
    }
}

/** docs/README.md: disabled controls sit at 38% opacity with no ripple. */
private const val DISABLED_ALPHA = 0.38f

/** docs/README.md: minimum touch target, whatever the glyph inside measures. */
private val TOUCH_TARGET = 76.dp
