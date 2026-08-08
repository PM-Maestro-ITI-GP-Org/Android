package com.motorguard.ivi.ui.diagnostics

import android.content.res.Configuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.components.Pill
import com.motorguard.ivi.ui.diagnostics.render.CarRenderState
import com.motorguard.ivi.ui.diagnostics.render.rememberCar3dRenderer
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * First-draft wide-landscape composition for the Diagnostics tab: a 3D car stage on the left,
 * three reserved panels on the right (health ring / component detail / alerts — Steps 4-5).
 * Designed against the 1828 x 1026 dp area the fragment container leaves after the rail and
 * status bar. No ViewModel yet: everything on screen is either static chrome or driven by
 * [com.motorguard.ivi.ui.diagnostics.render.Car3dRenderer]'s own load state.
 *
 * // Step 2: hoist focus + severity from DiagnosticsViewModel
 */
@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
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
 * 4. A loading/failure scrim that dissolves once the model is ready.
 */
@Composable
private fun CarStage(modifier: Modifier = Modifier) {
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

        renderer.Render(focus = null, onBackgroundTap = { }, modifier = Modifier.fillMaxSize())

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
// exactly why that chrome has to look intentional on its own.

@Preview(name = "Day", widthDp = 1828, heightDp = 1026)
@Composable
private fun DiagnosticsScreenDayPreview() {
    MotorGuardTheme { DiagnosticsScreen() }
}

@Preview(
    name = "Night",
    widthDp = 1828,
    heightDp = 1026,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DiagnosticsScreenNightPreview() {
    MotorGuardTheme { DiagnosticsScreen() }
}
