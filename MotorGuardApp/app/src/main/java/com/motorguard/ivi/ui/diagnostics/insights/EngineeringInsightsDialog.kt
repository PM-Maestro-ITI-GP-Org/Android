package com.motorguard.ivi.ui.diagnostics.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.vehicle.api.CaptureState
import com.motorguard.ivi.data.vehicle.api.MotorCapture
import com.motorguard.ivi.data.vehicle.api.MotorSignalGroup
import kotlinx.coroutines.delay

private object InsightsTuning {
    /** How long the opening speed-command view is held before the currents replace it. Long enough
     *  to read the shape of the run, short enough that nobody reaches for the screen first. */
    const val INTRO_HOLD_MILLIS = 2_600L

    /** The same, for the three-phase current view that follows it. */
    const val CURRENT_HOLD_MILLIS = 2_600L

    /** Degrees of scrub per pixel dragged, as a fraction of the visible window. Expressed against
     *  the WINDOW rather than the capture so a drag feels the same whether the view spans ten
     *  seconds or fifty milliseconds. */
    const val DRAG_WINDOWS_PER_WIDTH = 1f
}

/**
 * The three stages the popup moves through after a capture arrives.
 *
 * The first two are a scripted demonstration: they show the whole run, then the detail inside it,
 * so the switcher that appears afterwards is self-explanatory. The user is not locked out — any
 * interaction cuts straight to [Stage.INTERACTIVE].
 */
private enum class Stage { INTRO_SPEED, INTRO_CURRENT, INTERACTIVE }

/**
 * Full-screen engineering view of one requested capture.
 *
 * Dismissed by the close control or by tapping outside the panel. Deliberately not a `Dialog`:
 * the diagnostics screen sits over an opaque `SurfaceView`, and a separate dialog window composites
 * against the window behind it rather than against the stage, which reads as a second screen rather
 * than a layer over this one.
 */
@Composable
internal fun EngineeringInsightsDialog(
    state: CaptureState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f))
            // Tap anywhere outside the panel dismisses. `indication = null` because a ripple
            // spreading across the whole screen reads as the scrim being a control in itself.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                // Swallows taps that land on the panel, so the scrim's dismiss handler underneath
                // only ever sees taps that genuinely missed it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(Modifier.fillMaxSize()) {
                InsightsHeader(state = state, onRefresh = onRefresh, onDismiss = onDismiss)
                when (state) {
                    is CaptureState.Ready -> CaptureBody(state.capture)
                    CaptureState.Requesting -> CenteredStatus(
                        title = "Requesting capture",
                        detail = "Acquiring 20 kHz samples from the diagnostics unit",
                        busy = true,
                    )
                    is CaptureState.Failed -> CenteredStatus(
                        title = "Capture failed",
                        detail = state.message,
                        busy = false,
                        isError = true,
                    )
                    CaptureState.Idle -> CenteredStatus(
                        title = "No capture yet",
                        detail = "Request one to see the raw signals",
                        busy = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightsHeader(state: CaptureState, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Top-LEFT, which is unusual and deliberate: this panel is a drill-down, and on a
        // left-hand-drive head unit the near hand reaches the left edge without crossing the screen.
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Close engineering insights")
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Motor · engineering insights",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = when (state) {
                is CaptureState.Ready ->
                    "${(state.capture.durationSec).toInt()} s at 20 kHz"
                else -> ""
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
        )
        Spacer(Modifier.width(14.dp))
        TextButton(onClick = onRefresh, enabled = state !is CaptureState.Requesting) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Refresh data")
        }
    }
}

@Composable
private fun CenteredStatus(
    title: String,
    detail: String,
    busy: Boolean,
    isError: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
        )
    }
}

@Composable
private fun CaptureBody(capture: MotorCapture) {
    var stage by remember(capture) { mutableStateOf(Stage.INTRO_SPEED) }
    var chosen by remember(capture) { mutableStateOf(MotorSignalGroup.CURRENT) }

    // Shared across every signal group, which is the point: switching signal keeps you at the same
    // MOMENT in the run. Storing a time rather than a sample index is what lets groups with
    // different window lengths agree on where "here" is.
    var windowStartSec by remember(capture) { mutableFloatStateOf(0f) }

    val group = when (stage) {
        Stage.INTRO_SPEED -> MotorSignalGroup.SPEED_COMMAND
        Stage.INTRO_CURRENT -> MotorSignalGroup.CURRENT
        Stage.INTERACTIVE -> chosen
    }

    // Keyed on the capture, so a refresh replays the introduction against the new data rather than
    // dropping the user into whatever view they last had.
    LaunchedEffect(capture) {
        delay(InsightsTuning.INTRO_HOLD_MILLIS)
        if (stage == Stage.INTRO_SPEED) {
            stage = Stage.INTRO_CURRENT
            // Jump to the middle of the run before showing the currents. At t = 0 the motor has
            // not started, so a 50 ms window there is three flat lines — a technically correct
            // view of nothing, and a poor thing to open on. The scrubber states the time, so the
            // jump is visible rather than hidden.
            windowStartSec = capture.durationSec / 2f
        }
        delay(InsightsTuning.CURRENT_HOLD_MILLIS)
        if (stage == Stage.INTRO_CURRENT) stage = Stage.INTERACTIVE
    }

    val windowSec = minOf(group.windowSec, capture.durationSec)
    val maxStart = (capture.durationSec - windowSec).coerceAtLeast(0f)
    val start = windowStartSec.coerceIn(0f, maxStart)
    val fromIndex = (start * MotorCapture.SAMPLE_RATE_HZ).toInt()
    val toIndex = ((start + windowSec) * MotorCapture.SAMPLE_RATE_HZ).toInt()

    val channels = capture.channelsOf(group)
    val palette = phasePalette(MaterialTheme.colorScheme.primary, channels.size)

    Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp, bottom = 18.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = "${group.label} · ${group.unit}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            )
            Spacer(Modifier.height(10.dp))
            WaveformPlot(
                channels = channels,
                colors = palette,
                fromIndex = fromIndex,
                toIndex = toIndex,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(capture, group) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            // Any touch means the demonstration has served its purpose.
                            stage = Stage.INTERACTIVE
                            val perPx = windowSec * InsightsTuning.DRAG_WINDOWS_PER_WIDTH / size.width
                            // Dragging left moves FORWARD in time: the trace follows the finger,
                            // like pulling a paper tape past a window.
                            windowStartSec = (windowStartSec - dragAmount * perPx)
                                .coerceIn(0f, maxStart)
                        }
                    },
            )
            Spacer(Modifier.height(10.dp))
            TimeScrubber(
                startSec = start,
                windowSec = windowSec,
                totalSec = capture.durationSec,
            )
        }

        Spacer(Modifier.width(16.dp))

        // The switcher appears only once the scripted introduction is done, so the two opening
        // views are watched rather than clicked past before they have said anything.
        AnimatedVisibility(
            visible = stage == Stage.INTERACTIVE,
            enter = fadeIn(tween(260, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(160)),
        ) {
            Column(
                modifier = Modifier.width(158.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.height(24.dp))
                MotorSignalGroup.entries.forEach { entry ->
                    SignalChip(
                        label = entry.label,
                        selected = entry == group,
                        onClick = { chosen = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            color = fg,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

/** Where the visible window sits inside the whole capture. Read-only: the plot itself is the
 *  control, and a second draggable thing competing for the same gesture would be one too many. */
@Composable
private fun TimeScrubber(startSec: Float, windowSec: Float, totalSec: Float) {
    val fraction = (windowSec / totalSec).coerceIn(0.01f, 1f)
    val offset = if (totalSec > windowSec) startSec / totalSec else 0f
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .padding(start = 0.dp)
                    .offsetFraction(offset)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = timeLabel(startSec, windowSec),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/** Shifts a child by a fraction of the PARENT's width, which `Modifier.offset` cannot express
 *  because it only knows the child's own size. */
private fun Modifier.offsetFraction(fraction: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative((constraints.maxWidth * fraction).toInt(), 0)
        }
    },
)

private fun timeLabel(startSec: Float, windowSec: Float): String {
    val end = startSec + windowSec
    return if (windowSec < 1f) {
        "t = %.3f – %.3f s · swipe to scrub".format(startSec, end)
    } else {
        "t = %.2f – %.2f s · swipe to scrub".format(startSec, end)
    }
}
