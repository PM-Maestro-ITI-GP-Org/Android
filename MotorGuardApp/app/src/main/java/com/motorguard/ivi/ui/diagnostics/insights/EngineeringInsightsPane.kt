package com.motorguard.ivi.ui.diagnostics.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.vehicle.api.CaptureState
import com.motorguard.ivi.data.vehicle.api.MotorCapture
import com.motorguard.ivi.data.vehicle.api.MotorCaptureSummary
import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import com.motorguard.ivi.data.vehicle.api.MotorSignalGroup
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.SignalState
import com.motorguard.ivi.data.vehicle.api.latestValueOrNull
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.components.Pill
import com.motorguard.ivi.ui.diagnostics.component.CaptureBlock
import com.motorguard.ivi.ui.diagnostics.component.TelemetryFormat
import com.motorguard.ivi.ui.theme.SemanticColors
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
 * Engineering view of the motor's capture, as a tab inside the Diagnostics window rather than a
 * popup over it — the tab bar is the way in and out, so there is no scrim and no dismiss-by-tap.
 * [onBackToOverview] backs the header's "Overview" control, a shortcut for the same tab switch.
 *
 * Live: [state] is expected to keep arriving on its own (see [DiagnosticsViewModel]'s live-refresh
 * ticker) for as long as this is composed, which is what makes the plot track the motor rather
 * than showing one capture frozen from when the tab was opened.
 */
@Composable
internal fun EngineeringInsightsPane(
    state: CaptureState,
    /** The diagnostics unit's own live classification — the QNX guest's AI-result shared
     *  memory, over SOME/IP. Shown alongside the capture rather than folded into it: it updates
     *  on its own 1 Hz cadence, independent of whichever capture happens to be on screen. */
    motor: SignalState<MotorTelemetry>,
    /** The last capture's numbers, already reduced by [DiagnosticsViewModel] off the SPI
     *  samples — never recomputed here. */
    captureSummary: MotorCaptureSummary?,
    onRefresh: () -> Unit,
    onBackToOverview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxSize()) {
            InsightsHeader(state = state, onRefresh = onRefresh, onBackToOverview = onBackToOverview)
            when (state) {
                is CaptureState.Ready -> CaptureBody(state.capture, motor, captureSummary)
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

@Composable
private fun InsightsHeader(state: CaptureState, onRefresh: () -> Unit, onBackToOverview: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Top-LEFT, which is unusual and deliberate: this panel is a drill-down, and on a
        // left-hand-drive head unit the near hand reaches the left edge without crossing the screen.
        IconButton(onClick = onBackToOverview) {
            Icon(Icons.Filled.Close, contentDescription = "Back to Overview")
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
                    "${(state.capture.durationSec).toInt()} s at 20 kHz · live"
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
private fun CaptureBody(
    capture: MotorCapture,
    motor: SignalState<MotorTelemetry>,
    captureSummary: MotorCaptureSummary?,
) {
    // Deliberately NOT keyed on `capture` (it used to be): the tab now live-refreshes captures
    // every few seconds (see DiagnosticsViewModel's live-refresh ticker), and re-running the
    // scripted intro or snapping the scrub position back to the middle of the run on every one
    // of those ticks is the opposite of "live" — it would fight whatever the driver is currently
    // looking at. Keying on `capture` here is what used to make the panel feel like it kept
    // resetting itself instead of updating in place. These now persist for as long as this
    // composable stays alive, i.e. for as long as the Engineering tab is open, and reset only
    // when the tab is left and reopened (a fresh composition).
    var stage by remember { mutableStateOf(Stage.INTRO_SPEED) }
    var chosen by remember { mutableStateOf(MotorSignalGroup.CURRENT) }

    // Shared across every signal group, which is the point: switching signal keeps you at the same
    // MOMENT in the run. Storing a time rather than a sample index is what lets groups with
    // different window lengths agree on where "here" is.
    var windowStartSec by remember { mutableFloatStateOf(0f) }

    // Zoom is remembered PER SIGNAL, seeded from each signal's own default.
    //
    // One shared flag would force a single answer onto signals that want different ones: the
    // speeds are envelopes and open zoomed out, the currents are waveforms and open zoomed in.
    // What stays shared is the window START, so switching signal still lands on the same moment —
    // which is the property that actually matters for comparing them.
    val zoomOverrides = remember { mutableStateMapOf<MotorSignalGroup, Boolean>() }

    val group = when (stage) {
        Stage.INTRO_SPEED -> MotorSignalGroup.SPEED_COMMAND
        Stage.INTRO_CURRENT -> MotorSignalGroup.CURRENT
        Stage.INTERACTIVE -> chosen
    }

    // Runs once per tab-open (Unit key), not once per capture: with live-refresh this composable
    // now sees a new `capture` every few seconds, and replaying a 5.2 s scripted intro on top of
    // the driver's current view every tick would be the opposite of smooth. The very first
    // capture after opening the tab still gets the intro; a manual "Refresh data" tap or a live
    // tick afterwards just updates the data the current view is already showing.
    LaunchedEffect(Unit) {
        delay(InsightsTuning.INTRO_HOLD_MILLIS)
        if (stage == Stage.INTRO_SPEED) {
            stage = Stage.INTRO_CURRENT
            // Jump to the middle of the run before showing the currents. At t = 0 the motor has
            // not started, so a 50 ms window there is three flat lines — a technically correct
            // view of nothing, and a poor thing to open on. The scrubber states the time, so the
            // jump is visible rather than hidden.
            windowStartSec = capture.durationSec / 2f
            zoomOverrides[MotorSignalGroup.CURRENT] = true
        }
        delay(InsightsTuning.CURRENT_HOLD_MILLIS)
        if (stage == Stage.INTRO_CURRENT) stage = Stage.INTERACTIVE
    }

    val zoomed = zoomOverrides[group] ?: group.opensZoomedIn
    val windowSec = if (zoomed) {
        minOf(group.windowSec, capture.durationSec)
    } else {
        capture.durationSec
    }
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
                range = group.displayRange,
                fromIndex = fromIndex,
                toIndex = toIndex,
                startSec = start,
                windowSec = windowSec,
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
            CaptureNavigator(
                capture = capture,
                group = group,
                color = palette.first(),
                startSec = start,
                windowSec = windowSec,
                onPressAt = { fraction ->
                    stage = Stage.INTERACTIVE
                    // The pressed point becomes the CENTRE of the window, not its left edge: on a
                    // 50 ms window inside a 10 s capture, aligning the edge would put the thing
                    // the user pointed at just off the left of the plot.
                    windowStartSec = (fraction * capture.durationSec - windowSec / 2f)
                        .coerceIn(0f, maxStart)
                },
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.width(158.dp).fillMaxHeight()) {
            // The switcher appears only once the scripted introduction is done, so the two opening
            // views are watched rather than clicked past before they have said anything.
            AnimatedVisibility(
                visible = stage == Stage.INTERACTIVE,
                enter = fadeIn(tween(260, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(160)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Spacer(Modifier.weight(1f))

            // Bottom right, and deliberately OUTSIDE the switcher's AnimatedVisibility: the zoom is
            // available at any time, including during the opening sequence, for any signal.
            ZoomToggle(
                zoomed = zoomed,
                onToggle = {
                    stage = Stage.INTERACTIVE
                    // Zooming IN keeps the middle of the current view rather than its left edge,
                    // so the moment being looked at stays on screen instead of scrolling off it.
                    val centre = windowStartSec + windowSec / 2f
                    val next = if (zoomed) capture.durationSec else minOf(group.windowSec, capture.durationSec)
                    windowStartSec = (centre - next / 2f)
                        .coerceIn(0f, (capture.durationSec - next).coerceAtLeast(0f))
                    zoomOverrides[group] = !zoomed
                },
            )
        }

        Spacer(Modifier.width(16.dp))

        MotorStatsColumn(
            motor = motor,
            captureSummary = captureSummary,
            modifier = Modifier.width(240.dp).fillMaxHeight(),
        )
    }
}

/**
 * The AI classification and the last capture's derived numbers, alongside the plot rather than
 * only reachable from the Overview card — this is the QNX guest's own data end to end (the
 * SOME/IP event is its AI-result shared memory, the SOME/IP capture is its SPI shared memory),
 * never anything synthesised in this app.
 */
@Composable
private fun MotorStatsColumn(
    motor: SignalState<MotorTelemetry>,
    captureSummary: MotorCaptureSummary?,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier, padding = PaddingValues(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Diagnostics unit",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            )
            Spacer(Modifier.height(10.dp))
            MotorFaultDetail(motor)
            Spacer(Modifier.height(22.dp))
            Text(
                text = "Capture statistics",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            )
            Spacer(Modifier.height(10.dp))
            if (captureSummary != null) {
                CaptureBlock(captureSummary, motor.latestValueOrNull?.faultType ?: MotorFaultType.NORMAL)
            } else {
                Text(
                    text = "Waiting for the first capture to finish\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun MotorFaultDetail(motor: SignalState<MotorTelemetry>) {
    when (motor) {
        is SignalState.Live -> MotorFaultRows(motor.data, stale = false)
        is SignalState.Stale -> MotorFaultRows(motor.lastData, stale = true)
        SignalState.Offline -> Text(
            text = "No data \u2014 diagnostics unit offline",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        SignalState.Loading -> Text(
            text = "Connecting to the diagnostics unit\u2026",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun MotorFaultRows(data: MotorTelemetry, stale: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                text = when (data.faultType) {
                    MotorFaultType.NORMAL -> "OK"
                    MotorFaultType.ELECTRICAL -> "Electrical fault"
                    MotorFaultType.MECHANICAL -> "Mechanical fault"
                    MotorFaultType.SENSOR -> "Sensor fault"
                },
                bg = SemanticColors.forSeverity(data.faultSeverity),
            )
            if (stale) {
                Spacer(Modifier.width(8.dp))
                Pill(text = "Stale", bg = SemanticColors.caution)
            }
        }
        Spacer(Modifier.height(12.dp))
        val life = data.remainingLife
        Text(
            text = if (life != null) {
                "Remaining life: ${TelemetryFormat.hours(life.hours)}"
            } else {
                "No remaining-life estimate"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
        )
        if (life?.percent != null) {
            Text(
                text = TelemetryFormat.percent(life.percent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * Switches between the signal's own detail window and the whole capture.
 *
 * Always enabled: every signal now has both a detail window and a whole-capture view worth
 * looking at, they simply differ in which one they open on.
 */
@Composable
private fun ZoomToggle(zoomed: Boolean, onToggle: () -> Unit) {
    OutlinedButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = if (zoomed) Icons.Filled.ZoomOut else Icons.Filled.ZoomIn,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (zoomed) "Full run" else "Zoom in", maxLines = 1)
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

/**
 * A map of the whole capture with the visible window marked on it, and the control for moving that
 * window: press anywhere and that moment becomes the middle of the plot.
 *
 * This replaced a thin drag-only bar. Dragging a 6 dp track to travel a ten-second capture at a
 * 50 ms window meant aiming at a target a couple of pixels wide and then nudging it — the control
 * was the hard part, not the decision. Pressing is the decision: the strip shows the shape of the
 * run, so the peak, the ramp or the tail-off is visible BEFORE it is chosen rather than found by
 * hunting. Dragging still works, and keeps tracking the finger, but it is now the refinement
 * rather than the mechanism.
 */
@Composable
private fun CaptureNavigator(
    capture: MotorCapture,
    group: MotorSignalGroup,
    color: Color,
    startSec: Float,
    windowSec: Float,
    onPressAt: (Float) -> Unit,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val trace = color.copy(alpha = 0.55f)
    val windowFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val windowEdge = MaterialTheme.colorScheme.primary
    val channels = capture.channelsOf(group)
    val range = group.displayRange

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(NAVIGATOR_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .pointerInput(capture, group) {
                    // One gesture handler for press AND drag, rather than a tap detector stacked
                    // on a drag detector: stacked, whichever claimed the pointer first locked the
                    // other out, and a press that moved by a pixel resolved as neither. Acting on
                    // the down event is also what makes this feel immediate — there is no wait to
                    // see whether the finger is going to travel.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        onPressAt(down.position.x / size.width)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            onPressAt(change.position.x / size.width)
                            change.consume()
                        }
                    }
                },
        ) {
            drawRect(track)

            // The whole capture, decimated to the strip's width. Cheap: one pass over the samples
            // at a width of a few hundred columns.
            val columns = size.width.toInt().coerceAtLeast(1)
            val span = (range.endInclusive - range.start).takeIf { it > 1e-6f } ?: 1f
            channels.forEach { channel ->
                val decimated = minMaxDecimate(channel, 0, channel.size, columns)
                for (c in 0 until columns) {
                    val yLo = (size.height * (1f - (decimated[c * 2] - range.start) / span))
                        .coerceIn(0f, size.height)
                    val yHi = (size.height * (1f - (decimated[c * 2 + 1] - range.start) / span))
                        .coerceIn(0f, size.height)
                    drawLine(trace, Offset(c.toFloat(), yLo), Offset(c.toFloat(), yHi), strokeWidth = 1f)
                }
            }

            // The visible window, drawn over the map. At a 50 ms window inside ten seconds this is
            // sub-pixel wide, so it is floored at something a thumb can see and aim at.
            val total = capture.durationSec
            val x = size.width * (startSec / total)
            val w = (size.width * (windowSec / total)).coerceAtLeast(3.dp.toPx())
            drawRect(windowFill, topLeft = Offset(x, 0f), size = Size(w, size.height))
            drawRect(
                windowEdge,
                topLeft = Offset(x, 0f),
                size = Size(w, size.height),
                style = Stroke(width = 1.5.dp.toPx()),
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

/** Tall enough to show the shape of the run and to be pressed accurately on a moving vehicle. */
private val NAVIGATOR_HEIGHT = 58.dp

private fun timeLabel(startSec: Float, windowSec: Float): String {
    val end = startSec + windowSec
    return if (windowSec < 1f) {
        "t = %.3f – %.3f s · press the strip to move".format(startSec, end)
    } else {
        "t = %.2f – %.2f s · press the strip to move".format(startSec, end)
    }
}
