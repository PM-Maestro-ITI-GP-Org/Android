package com.motorguard.ivi.ui.diagnostics.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.ui.diagnostics.DiagnosticsUiState
import com.motorguard.ivi.ui.diagnostics.render.CarRenderer
import com.motorguard.ivi.ui.theme.SemanticColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive

/**
 * Mirrors `res/values/dimens.xml` (`touch_min` = 76dp, `hotspot_dot` = 32dp), which stays the
 * source of truth for those values. Re-declared as Compose constants rather than read via
 * `dimensionResource()` because: nothing else in this file uses resources; `dimensionResource`
 * needs a `LocalContext`, which a pure-Compose overlay otherwise has no reason to depend on;
 * `@Preview` would then depend on resource resolution; and Step 2 must not touch `res/`. If
 * `dimens.xml` ever changes these two values, change these two lines to match.
 */
private object HotspotTokens {
    /** Automotive touch-target minimum — invisible, just the hit area. */
    val touchTarget = 76.dp

    /** The visible dot. */
    val dot = 32.dp
    const val pulseMillis = 1400

    /**
     * Resting opacity. Eight opaque dots compete with the car for attention; at rest they should
     * read as a quiet annotation layer, not as the subject.
     */
    const val IDLE_ALPHA = 0.42f

    /** Opacity while hovered, pressed, or focused — the dot the user is addressing. */
    const val ACTIVE_ALPHA = 1f

    /**
     * CAUTION and CRITICAL dots never fade to [IDLE_ALPHA]. A fault must be equally visible
     * whether or not a pointer happens to be near it — the phase-1 brief's escalate-fast rule
     * ("never delay showing a problem") applies to visual weight, not just to timing.
     */
    const val ALERT_ALPHA = 1f

    /** Long enough to read as a deliberate response, short enough to feel immediate. */
    const val EMPHASIS_MILLIS = 160
}

/**
 * Per-frame screen-position cache for all 8 hotspots, written once per frame from
 * [HotspotOverlay]'s frame loop and read from Compose's layout/draw phases.
 *
 * A naive `mutableStateOf<Offset>` per dot per frame is wrong three times over: 8 state writes a
 * frame each schedule a *recomposition*; recomposition re-runs [SemanticColors.forSeverity] and
 * every modifier factory on all 8 dots; and it happens even when nothing moved (a static hero
 * camera, which is most of the time). The fix uses Compose's phase-aware snapshot reads: a state
 * read inside `Modifier.offset { }` invalidates layout only, inside `Modifier.graphicsLayer { }`
 * invalidates draw only — neither re-runs composition.
 */
@Stable
class HotspotProjector {
    private val n = Hotspot.entries.size
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val shown = BooleanArray(n)

    /** The ONE snapshot cell backing all 8 dots. Bumped only when something actually moved. */
    private var version by mutableIntStateOf(0)
    private fun observe(): Int = version

    /** Call exactly once per frame, on the main thread (see [HotspotOverlay]'s `LaunchedEffect`). */
    fun update(renderer: CarRenderer, active: Boolean) {
        var changed = false
        for (h in Hotspot.entries) {
            val i = h.ordinal
            val p = if (active) renderer.screenPositionOf(h) else null
            if ((p != null) != shown[i]) {
                shown[i] = p != null
                changed = true
            }
            if (p != null) {
                if (abs(p.x - x[i]) > 0.5f || abs(p.y - y[i]) > 0.5f) changed = true
                x[i] = p.x
                y[i] = p.y // keep last-known when null, so no jump when the dot returns
            }
        }
        if (changed) version++
    }

    /** Layout-phase read. Hidden dots park off-screen so an alpha-0 hit target cannot eat
     *  background taps (a 76 dp Box at [Offset.Zero] would otherwise sit right over the car). */
    fun centerOf(hotspot: Hotspot): IntOffset {
        observe()
        val i = hotspot.ordinal
        return if (shown[i]) IntOffset(x[i].roundToInt(), y[i].roundToInt()) else OFF_SCREEN
    }

    /** Draw-phase read. */
    fun alphaOf(hotspot: Hotspot): Float {
        observe()
        return if (shown[hotspot.ordinal]) 1f else 0f
    }

    /** NO snapshot read — for the click handler, which must not register a recompose-triggering
     *  read just to decide whether a stale tap should be ignored. */
    fun isShownNow(hotspot: Hotspot): Boolean = shown[hotspot.ordinal]

    private companion object {
        val OFF_SCREEN = IntOffset(-10_000, -10_000)
    }
}

/**
 * The 8 tappable dots over the 3D car. MUST be placed in the SAME `Box` as
 * [CarRenderer.Render], AFTER it, at the same size: [CarRenderer.screenPositionOf] returns px
 * relative to the render area's top-left, so the two need a shared coordinate space, and
 * declaring this composable after the Scene also guarantees its frame loop is forgotten (and
 * therefore stops touching Filament) BEFORE the renderer's native objects are destroyed — see
 * the `DisposableEffect` ordering note in `Car3dRenderer.Render`.
 */
@Composable
fun HotspotOverlay(
    renderer: CarRenderer,
    state: DiagnosticsUiState,
    active: Boolean,
    onHotspotTap: (Hotspot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val projector = remember { HotspotProjector() }

    // Deliberately not `Scene(onFrame = ...)`: that would force an `onFrame` hook into
    // `CarRenderer`, which a hypothetical 2D renderer has no business implementing.
    // `withFrameNanos` runs on the same Choreographer Filament's own frame pump uses, so worst
    // case the dots lag one frame — invisible in practice.
    LaunchedEffect(renderer, projector, active) {
        while (isActive) {
            withFrameNanos { }
            projector.update(renderer, active)
        }
    }

    val pulse = rememberInfiniteTransition(label = "hotspotPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(HotspotTokens.pulseMillis, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "hotspotPulseValue",
    ) // `val`, NOT `by`: reading this with `by` here is a composition read, which would
    // recompose the whole overlay every frame and defeat everything [HotspotProjector] buys.

    Box(modifier) {
        Hotspot.entries.forEach { hotspot ->
            val severity = state.severityOf(hotspot) // recomposes only when THIS dot's severity flips
            val color = SemanticColors.forSeverity(severity)
            val hasSignal = state.hasSignal(hotspot)
            val pulsing = severity == Severity.CAUTION || severity == Severity.CRITICAL
            val focusedNow = state.focusedHotspot == hotspot
            val ringColor = MaterialTheme.colorScheme.background

            // Hover covers mouse and stylus; press is its touch-screen equivalent, so the dot
            // responds identically on the emulator and on the real head unit. Both come from one
            // InteractionSource shared with `clickable`, which already emits press for free.
            val interactions = remember { MutableInteractionSource() }
            val hovered by interactions.collectIsHoveredAsState()
            val pressed by interactions.collectIsPressedAsState()

            val emphasised = hovered || pressed || focusedNow || pulsing
            // A composition read, but it only changes on hover/press/severity edges — never per
            // frame — so it does not reintroduce the recomposition storm HotspotProjector avoids.
            val emphasisAlpha = animateFloatAsState(
                targetValue = when {
                    pulsing -> HotspotTokens.ALERT_ALPHA
                    emphasised -> HotspotTokens.ACTIVE_ALPHA
                    else -> HotspotTokens.IDLE_ALPHA
                },
                animationSpec = tween(HotspotTokens.EMPHASIS_MILLIS, easing = FastOutSlowInEasing),
                label = "hotspotEmphasis",
            ) // `val`, not `by` — read inside graphicsLayer so it invalidates draw only.

            Box(
                modifier = Modifier
                    .size(HotspotTokens.touchTarget)
                    .offset {
                        val halfPx = (HotspotTokens.touchTarget.toPx() / 2f).roundToInt()
                        projector.centerOf(hotspot) - IntOffset(halfPx, halfPx)
                    }
                    // Visibility (is it projectable at all?) times emphasis (is the user
                    // addressing it?). Multiplying keeps the two concerns independent.
                    .graphicsLayer {
                        alpha = projector.alphaOf(hotspot) * emphasisAlpha.value
                    }
                    .clip(CircleShape)
                    .hoverable(interactions)
                    .clickable(
                        interactionSource = interactions,
                        indication = null, // the alpha change IS the feedback
                        role = Role.Button,
                        onClickLabel = hotspot.label,
                    ) {
                        if (projector.isShownNow(hotspot)) onHotspotTap(hotspot) // no snapshot read
                    },
                contentAlignment = Alignment.Center,
            ) {
                HotspotDot(
                    color = color,
                    hasSignal = hasSignal,
                    pulsing = pulsing,
                    focused = focusedNow,
                    ringColor = ringColor,
                    pulse = pulse,
                )
            }
        }
    }
}

/**
 * Single small canvas per dot. All animated values are read INSIDE the draw lambda — see the
 * `pulse` comment in [HotspotOverlay] for why that matters.
 *
 * Known and accepted for Step 2: dots on the far side of the car are not depth-tested against
 * the model and draw over the bodywork. Spec §7 requires all 8 dots visible at idle framing —
 * this is correct behaviour here, not a bug to fix.
 */
@Composable
private fun HotspotDot(
    color: Color,
    hasSignal: Boolean,
    pulsing: Boolean,
    focused: Boolean,
    ringColor: Color,
    pulse: State<Float>,
) {
    Canvas(modifier = Modifier.size(HotspotTokens.touchTarget)) {
        val p = pulse.value // draw-phase read
        val r = HotspotTokens.dot.toPx() / 2f

        if (pulsing) {
            drawCircle(
                color = color.copy(alpha = 0.42f * (1f - p)),
                radius = r * (1f + 0.55f * p),
            )
        }
        // Contrast halo so the dot reads against bodywork of any colour.
        drawCircle(color = ringColor.copy(alpha = 0.55f), radius = r + 2.dp.toPx())
        drawCircle(color = color.copy(alpha = if (hasSignal) 1f else 0.55f), radius = r)
        // Glanceable core — a small bright centre reads as "dot" faster than a flat disc.
        drawCircle(color = Color.White.copy(alpha = 0.85f), radius = r * 0.30f)
        if (focused) {
            drawCircle(color = color, radius = r * 1.75f, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
