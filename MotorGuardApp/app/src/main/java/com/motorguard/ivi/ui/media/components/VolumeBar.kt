package com.motorguard.ivi.ui.media.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.data.media.VolumeController
import com.motorguard.ivi.data.media.VolumeState
import com.motorguard.ivi.ui.theme.MotorGuard
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Media volume: a mute toggle and a slider.
 *
 * This started as a hand-drawn track with its own `pointerInput`, to match the scrubber's flat
 * look. It rendered correctly and ignored every touch — tap, drag and the mute icon alike — so
 * it was replaced with the framework [Slider], which brings its own gesture handling, hit target
 * and accessibility. A control that works and looks close beats one that looks exact and does
 * nothing.
 *
 * The position is written straight through to the platform while dragging, so the speakers
 * follow the thumb. Between drags the slider shows the *platform's* value, which is what keeps
 * it correct when the volume moves from somewhere else — the steering-wheel keys, or a phone
 * doing AVRCP absolute volume.
 */
@Composable
fun VolumeBar(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val controller = remember { VolumeController(context) }

    val stateFlow = remember { MutableStateFlow(controller.current()) }
    val state by stateFlow.collectAsStateWithLifecycle()

    LaunchedEffect(controller) {
        controller.stream().collect { stateFlow.value = it }
    }

    // While the thumb is held, the slider owns the position — otherwise a volume-changed
    // broadcast arriving mid-drag would yank it back under the driver's finger.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else state.fraction

    val colors = MotorGuard.colors
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
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))

        Slider(
            value = if (state.muted) 0f else value,
            onValueChange = {
                dragging = true
                dragValue = it
                controller.setFraction(it)
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.onBaseDim.copy(alpha = 0.22f),
            ),
        )
    }
}

private fun VolumeState.icon() = when {
    muted || level == 0 -> Icons.AutoMirrored.Filled.VolumeOff
    fraction < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
    else -> Icons.AutoMirrored.Filled.VolumeUp
}
