package com.motorguard.ivi.ui.diagnostics

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.components.Pill
import com.motorguard.ivi.ui.diagnostics.component.HotspotOverlay
import com.motorguard.ivi.ui.diagnostics.debug.FakeDataControlPanel
import com.motorguard.ivi.ui.diagnostics.render.CarRenderState
import com.motorguard.ivi.ui.diagnostics.render.rememberCar3dRenderer
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Fragment-facing entry point: binds [DiagnosticsViewModel] and hands its state down to
 * [DiagnosticsScreenContent]. Kept separate from the content composable so `@Preview` never
 * needs a `ViewModelStoreOwner` — `viewModel()` throws with a null owner — and so opening this
 * screen in the IDE renderer never boots [VehicleData]'s process-lifetime ticker.
 */
@Composable
fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    DiagnosticsScreenContent(
        ui = ui,
        debugControls = viewModel.debugControls,
        onHotspotTap = viewModel::onHotspotTap,
        onBackgroundTap = viewModel::onBackgroundTap,
        onStageLongPress = viewModel::onStageLongPress,
        onDebugDismiss = viewModel::onDebugPanelDismiss,
        onUserInteraction = viewModel::onUserInteraction,
        modifier = modifier,
    )
}

/**
 * Wide-landscape composition for the Diagnostics tab: a 3D car stage on the left, three reserved
 * panels on the right (health ring / component detail / alerts — Steps 4-5), and the debug drawer
 * as the top-most layer. Designed against the 1828 x 1026 dp area the fragment container leaves
 * after the rail and status bar.
 *
 * Pure function of [ui] plus a handful of callbacks — no ViewModel reference, no fake-source
 * reference — so it is what both [DiagnosticsScreen] and every `@Preview` below actually render.
 */
@Composable
internal fun DiagnosticsScreenContent(
    ui: DiagnosticsUiState,
    /** Null in previews: previews render chrome only, with nothing wired to drive it. */
    debugControls: VehicleDebugControls?,
    onHotspotTap: (Hotspot) -> Unit,
    onBackgroundTap: () -> Unit,
    onStageLongPress: () -> Unit,
    onDebugDismiss: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberUpdatedState because pointerInput(Unit) captures its lambda for the whole
    // composition, and the callback identity changes on every recomposition of the caller.
    val latestInteraction by rememberUpdatedState(onUserInteraction)

    Box(
        modifier
            .fillMaxSize()
            // Resets the auto-return timer (spec §7: "8-10 s of NO INTERACTION"). Deliberately
            // the whole screen rather than just the car stage — reading the alert list or a live
            // card is interaction too, and auto-returning out from under someone mid-read would
            // be worse than not having the feature.
            //
            // PointerEventPass.Initial sees every press BEFORE any child does and consumes
            // nothing, so no existing gesture changes behaviour. Filtered to Press so dragging
            // does not cancel and relaunch the timer coroutine on every motion sample.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) latestInteraction()
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Diagnostics",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "Porsche Mission E",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    )
                }
                Spacer(Modifier.weight(1f))
                Pill(text = "Preview", bg = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                CarStage(
                    ui = ui,
                    onHotspotTap = onHotspotTap,
                    onBackgroundTap = onBackgroundTap,
                    onLongPress = onStageLongPress,
                    modifier = Modifier
                        .weight(1.35f)
                        .fillMaxHeight(),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // These three panels are exactly the Step 5 health ring, Step 4 live card and
                    // Step 5 alert list. Their relative weights are the layout reservation for them.
                    ReservedPanel(
                        title = "Vehicle health",
                        hint = "Health score appears here once telemetry is connected",
                        modifier = Modifier.weight(1f),
                    )
                    ReservedPanel(
                        title = "Component detail",
                        hint = "Select a component on the car to inspect it",
                        modifier = Modifier.weight(1.2f),
                    )
                    ReservedPanel(
                        title = "Alerts",
                        hint = "Severity alerts appear here",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Top-most layer, so it sits over both the car stage and the reserved panels. Null in
        // previews (see [debugControls]'s KDoc) — nothing to drive it with there anyway.
        if (debugControls != null) {
            AnimatedVisibility(
                visible = ui.debugPanelVisible,
                enter = fadeIn() + slideInHorizontally { it },
                exit = fadeOut() + slideOutHorizontally { it },
            ) {
                FakeDataControlPanel(controls = debugControls, onDismiss = onDebugDismiss)
            }
        }
    }
}

/**
 * Placeholder for a not-yet-built panel. Copy rule: no panel may state a vehicle fact — "No
 * alerts" or "All systems nominal" are claims about the *car*, and there is no data source
 * behind this screen yet to back them. [hint] only ever describes the UI, never the vehicle.
 */
@Composable
private fun ReservedPanel(title: String, hint: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * The 3D car stage. Layers, back to front:
 * 1. A flat [stageColor] base — what shows before the SurfaceView attaches, and the colour the
 *    Filament clear colour is matched to (see `applyStageColor` in `Car3dRenderer`).
 * 2. The SceneView `Scene` itself, opaque and Z-ordered *below* the window (`Car3dRenderer`
 *    sets this explicitly) so that everything below composites visually on top of it.
 * 3. A Canvas that rounds the corners the SurfaceView cannot clip and redraws GlassCard's
 *    border on top, so the stage reads as one more glass panel among the others.
 * 4. A gesture layer that turns a plain tap into [onBackgroundTap] and a long-press into
 *    [onLongPress] (the debug-panel reveal). Compose hit-tests topmost-first, so this consumes
 *    the touch down before the `Scene` underneath (layer 2) ever sees it — Step 1's
 *    `Scene(onTouchEvent = …)` background-tap hook is therefore dead code as of Step 2. Left in
 *    place rather than deleted: it is a hook into hit-testing against the model itself, which
 *    Step 3 may want back for tap-to-focus.
 * 5. [HotspotOverlay] — the 8 dots. Declared after layer 4, so a dot gets first refusal on a tap
 *    over the gesture layer's blanket background handler.
 * 6. A loading/failure scrim that dissolves once the model is ready.
 */
@Composable
private fun CarStage(
    ui: DiagnosticsUiState,
    onHotspotTap: (Hotspot) -> Unit,
    onBackgroundTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cornerRadius = 26.dp
    val page = MaterialTheme.colorScheme.background
    // The opaque colour a GlassCard visually resolves to. Filament clears to exactly this, so
    // the SurfaceView rectangle is indistinguishable from the surrounding panels.
    val stageColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f).compositeOver(page)

    val renderer = rememberCar3dRenderer(stageColor = stageColor)

    Box(modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(stageColor),
        )

        renderer.Render(
            focus = ui.focusedHotspot,
            onBackgroundTap = onBackgroundTap,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = cornerRadius.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1.0f to page.copy(alpha = 0.55f),
                ),
            )
            // The SurfaceView underneath is a hard rectangle; this masks it back down to the
            // panel's rounded corners.
            val mask = Path().apply {
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(r, r)))
                fillType = PathFillType.EvenOdd
            }
            drawPath(mask, color = page)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.08f),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onBackgroundTap() },
                        onLongPress = { onLongPress() },
                    )
                },
        )

        HotspotOverlay(
            renderer = renderer,
            state = ui,
            active = renderer.state is CarRenderState.Ready,
            onHotspotTap = onHotspotTap,
            modifier = Modifier.fillMaxSize(),
        )

        val scrimAlpha by animateFloatAsState(
            targetValue = if (renderer.state is CarRenderState.Ready) 0f else 1f,
            animationSpec = tween(450, easing = FastOutSlowInEasing),
            label = "carStageScrim",
        )
        if (scrimAlpha > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(stageColor.copy(alpha = scrimAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                when (val s = renderer.state) {
                    is CarRenderState.Loading -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Loading vehicle model",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    is CarRenderState.Failed -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = "Vehicle model unavailable",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    CarRenderState.Ready -> Unit
                }
            }
        }
    }
}

// Previews show only the Loading/Failed chrome (Filament doesn't run in the IDE renderer) —
// exactly why that chrome has to look intentional on its own. `debugControls = null` and a
// literal [DiagnosticsUiState] keep these independent of both `viewModel()` and [VehicleData].

@Preview(name = "Day", widthDp = 1828, heightDp = 1026)
@Composable
private fun DiagnosticsScreenDayPreview() {
    MotorGuardTheme {
        DiagnosticsScreenContent(DiagnosticsUiState(), null, {}, {}, {}, {}, {})
    }
}

@Preview(
    name = "Night",
    widthDp = 1828,
    heightDp = 1026,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DiagnosticsScreenNightPreview() {
    MotorGuardTheme {
        DiagnosticsScreenContent(DiagnosticsUiState(), null, {}, {}, {}, {}, {})
    }
}
