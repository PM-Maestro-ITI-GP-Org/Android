package com.motorguard.ivi.ui.media.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.data.media.VolumeController
import com.motorguard.ivi.data.media.VolumeState
import com.motorguard.ivi.ui.theme.MotorGuard
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Media volume: a mute toggle and a draggable bar.
 *
 * A bar rather than a Material `Slider` for the same reason the scrubber is hand-drawn — the
 * stock thumb is a small target and the design's is a wide, flat track that reads at a glance
 * from a driving position.
 *
 * Dragging writes straight through to the platform rather than holding a local position: the
 * volume is shared with the steering-wheel keys and with whatever else is playing, and a control
 * with its own private idea of the level would fight them.
 */
@Composable
fun VolumeBar(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val controller = remember { VolumeController(context) }

    // Seeded from the current value so the first frame is right rather than zero.
    val stateFlow = remember { MutableStateFlow(controller.current()) }
    val state by stateFlow.collectAsStateWithLifecycle()

    DisposableEffect(controller) {
        stateFlow.value = controller.current()
        onDispose { }
    }
    androidx.compose.runtime.LaunchedEffect(controller) {
        controller.stream().collect { stateFlow.value = it }
    }

    val colors = MotorGuard.colors
    val trackHeight = if (compact) 6.dp else 8.dp
    val iconSize = if (compact) 20.dp else 24.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = state.icon(),
            contentDescription = if (state.muted) "Unmute" else "Mute",
            tint = if (state.muted) colors.caution else colors.onBaseDim,
            modifier = Modifier
                .size(iconSize)
                .clickable { controller.toggleMute() },
        )
        Spacer(Modifier.width(if (compact) 10.dp else 14.dp))

        var trackWidth by remember { mutableStateOf(1) }
        Box(
            modifier = Modifier
                .weight(1f)
                // The touch area is taller than the drawn track: a 6 dp bar is not a target.
                .height(44.dp)
                .onSizeChanged { trackWidth = it.width.coerceAtLeast(1) }
                .pointerInput(controller) {
                    detectHorizontal(
                        onPosition = { x -> controller.setFraction(x / trackWidth) },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(50))
                    .background(colors.onBaseDim.copy(alpha = 0.22f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (state.muted) 0f else state.fraction)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent),
            )
        }
    }
}

/** Tap anywhere on the track, or drag along it — both set an absolute position. */
private suspend fun PointerInputScope.detectHorizontal(onPosition: (Float) -> Unit) {
    detectHorizontalDragGestures(
        onDragStart = { offset: Offset -> onPosition(offset.x) },
        onHorizontalDrag = { change, _ ->
            onPosition(change.position.x)
            change.consume()
        },
    )
}

private fun VolumeState.icon() = when {
    muted || level == 0 -> Icons.AutoMirrored.Filled.VolumeOff
    fraction < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
    else -> Icons.AutoMirrored.Filled.VolumeUp
}
