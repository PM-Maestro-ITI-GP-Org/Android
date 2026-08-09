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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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

    /**
     * The component glyph inside the dot. Sized so the disc keeps a ring of its severity colour
     * all the way round the icon — the fill is still what carries severity, the glyph only says
     * *which* component it belongs to.
     */
    val glyph = 17.dp
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

    /**
     * Resting alpha for a dot whose anchor is on the hidden side of the car: enough to hint that
     * the annotation layer wraps around the vehicle, not enough to compete with, or be mistaken
     * for, a near-side dot.
     */
    const val OCCLUDED_ALPHA = 0.05f

    /**
     * Floor for a CAUTION/CRITICAL dot on the hidden side. Deliberately NOT [ALERT_ALPHA].
     *
     * The idle fade exempts alerts because a de-emphasised dot is still in the right place. An
     * occluded dot is not: it draws over near-side bodywork, so at full brightness it MISSTATES
     * which side of the car the fault is on — with two tire dots ~60 px apart the driver cannot
     * tell which wheel is critical, which is worse than a dim one. Reachability is restored by
     * the alert list's jump-to-component (spec §7), not by brightness.
     */
    const val OCCLUDED_ALERT_ALPHA = 0.22f

    /** Past this occlusion the 76 dp hit target is withdrawn entirely. */
    const val OCCLUSION_TAP_CUTOFF = 0.5f

    /** Alpha of the non-focused dots while a component is focused (spec §7: "fade other dots"). */
    const val UNFOCUSED_ALPHA = 0.10f

    /** Floor for a non-focused CAUTION/CRITICAL dot while another component is focused. */
    const val UNFOCUSED_ALERT_ALPHA = 0.30f

    /** Spec §7: the other dots fade over 150-200 ms when a component is focused. */
    const val FOCUS_FADE_MILLIS = 180
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
    private val occ = FloatArray(n)
    private val tappable = BooleanArray(n)

    /** The ONE snapshot cell backing all 8 dots. Bumped only when something actually moved. */
    private var version by mutableIntStateOf(0)
    private fun observe(): Int = version

    /**
     * Call exactly once per frame, on the main thread (see [HotspotOverlay]'s `LaunchedEffect`).
     *
     * [alerting] marks the hotspots currently at CAUTION or CRITICAL, and [targetPx] is the touch
     * target diameter — both are needed to decide tappability, see [isTappableNow].
     */
    fun update(
        renderer: CarRenderer,
        active: Boolean,
        alerting: BooleanArray,
        targetPx: Float,
    ) {
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

            // Occlusion is per-frame, camera-derived data exactly like position, so it rides the
            // same diffing pipeline rather than getting its own state cell.
            val o = if (active) renderer.occlusionOf(h) else 0f
            if (abs(o - occ[i]) > 0.01f) changed = true
            occ[i] = o
        }

        // Tappability, resolved after every position is known because it depends on the others.
        for (h in Hotspot.entries) {
            val i = h.ordinal
            val t = shown[i] && when {
                occ[i] < HotspotTokens.OCCLUSION_TAP_CUTOFF -> true
                // A far-side component with a fault must stay reachable directly — an alert the
                // driver can see but not touch is a dead end. The exception is withdrawn only when
                // honouring it would steal a tap from a NEAR-side dot sitting on top of it, which
                // is the defect the occlusion cutoff exists to prevent in the first place. In that
                // case the alert list is still a route to it.
                alerting[i] -> !overlapsNearSideDot(i, targetPx)
                else -> false
            }
            if (t != tappable[i]) {
                tappable[i] = t
                changed = true
            }
        }

        if (changed) version++
    }

    /** True when dot [i] sits within one touch target of any dot on the visible side of the car. */
    private fun overlapsNearSideDot(i: Int, targetPx: Float): Boolean {
        for (j in 0 until n) {
            if (j == i || !shown[j]) continue
            if (occ[j] >= HotspotTokens.OCCLUSION_TAP_CUTOFF) continue
            val dx = x[j] - x[i]
            val dy = y[j] - y[i]
            if (dx * dx + dy * dy < targetPx * targetPx) return true
        }
        return false
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

    /** Draw-phase read. 0 = on the visible side of the car, 1 = fully behind it. */
    fun occlusionOf(hotspot: Hotspot): Float {
        observe()
        return occ[hotspot.ordinal]
    }

    /**
     * Layout-phase read. Displacement applied to the HIT TARGET only, so an occluded dot keeps
     * its faint visual while ceasing to eat taps aimed at the near-side dot beside it.
     */
    fun tapOffsetOf(hotspot: Hotspot): IntOffset {
        observe()
        return if (isTappableNow(hotspot)) IntOffset.Zero else OFF_SCREEN
    }

    /** NO snapshot read — for the click handler, which must not register a recompose-triggering
     *  read just to decide whether a stale tap should be ignored. Computed once per frame in
     *  [update]; see there for the occluded-but-alerting exception. */
    fun isTappableNow(hotspot: Hotspot): Boolean = tappable[hotspot.ordinal]

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

    // Which hotspots are alerting, and how big a touch target is in pixels — both needed by
    // HotspotProjector.update to decide whether an occluded dot stays tappable. Recomputed only
    // when a severity actually flips, not per frame.
    val alerting = remember(state.severities) {
        BooleanArray(Hotspot.entries.size) { index ->
            val severity = state.severityOf(Hotspot.entries[index])
            severity == Severity.CAUTION || severity == Severity.CRITICAL
        }
    }
    val targetPx = with(LocalDensity.current) { HotspotTokens.touchTarget.toPx() }

    // Deliberately not `Scene(onFrame = ...)`: that would force an `onFrame` hook into
    // `CarRenderer`, which a hypothetical 2D renderer has no business implementing.
    // `withFrameNanos` runs on the same Choreographer Filament's own frame pump uses, so worst
    // case the dots lag one frame — invisible in practice.
    LaunchedEffect(renderer, projector, active, alerting, targetPx) {
        while (isActive) {
            withFrameNanos { }
            projector.update(renderer, active, alerting, targetPx)
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
            val focusActive = state.focusedHotspot != null
            val dimmedByFocus = focusActive && !focusedNow

            // A composition read, but it only changes on hover/press/severity/focus edges — never
            // per frame — so it does not reintroduce the recomposition storm HotspotProjector
            // exists to avoid.
            val emphasisAlpha = animateFloatAsState(
                targetValue = when {
                    focusedNow -> HotspotTokens.ACTIVE_ALPHA
                    // Checked BEFORE `pulsing`: spec §7 fades the other dots while a component is
                    // focused, alerting or not. Alerts keep a higher floor and never vanish, and
                    // the state is transient anyway (8-10 s auto-return).
                    dimmedByFocus && pulsing -> HotspotTokens.UNFOCUSED_ALERT_ALPHA
                    dimmedByFocus -> HotspotTokens.UNFOCUSED_ALPHA
                    pulsing -> HotspotTokens.ALERT_ALPHA
                    emphasised -> HotspotTokens.ACTIVE_ALPHA
                    else -> HotspotTokens.IDLE_ALPHA
                },
                animationSpec = tween(
                    durationMillis = if (focusActive) {
                        HotspotTokens.FOCUS_FADE_MILLIS
                    } else {
                        HotspotTokens.EMPHASIS_MILLIS
                    },
                    easing = FastOutSlowInEasing,
                ),
                label = "hotspotEmphasis",
            ) // `val`, not `by` — read inside graphicsLayer so it invalidates draw only.

            // Plain Float resolved at composition: depends only on severity, never per frame.
            val occludedFloor = if (pulsing) {
                HotspotTokens.OCCLUDED_ALERT_ALPHA
            } else {
                HotspotTokens.OCCLUDED_ALPHA
            }

            Box(
                modifier = Modifier
                    .size(HotspotTokens.touchTarget)
                    .offset {
                        val halfPx = (HotspotTokens.touchTarget.toPx() / 2f).roundToInt()
                        projector.centerOf(hotspot) - IntOffset(halfPx, halfPx)
                    }
                    // Visibility (is it projectable at all?) times emphasis (is the user
                    // addressing it?) faded by occlusion (is it round the far side?). Keeping the
                    // three independent is what lets each be reasoned about on its own.
                    .graphicsLayer {
                        // The focused dot is never occlusion-faded: a mid-transition frame must
                        // not dim the very component the user just tapped.
                        val o = if (focusedNow) 0f else projector.occlusionOf(hotspot)
                        val e = emphasisAlpha.value
                        // Interpolate toward min(e, floor), never toward the floor itself, so
                        // occlusion can only ever DIM a dot — never brighten one that focus has
                        // already faded below the occluded floor.
                        alpha = projector.alphaOf(hotspot) * (e + (minOf(e, occludedFloor) - e) * o)
                    },
                contentAlignment = Alignment.Center,
            ) {
                HotspotDot(
                    hotspot = hotspot,
                    color = color,
                    hasSignal = hasSignal,
                    pulsing = pulsing,
                    focused = focusedNow,
                    ringColor = ringColor,
                    pulse = pulse,
                )

                // The hit target lives in its OWN box, separate from the visual, so it can be
                // withdrawn while the dot stays faintly drawn. An occluded dot has drifted onto
                // the wrong side of the car; leaving a 76 dp target there means it keeps stealing
                // taps aimed at the near-side dot beside it (measured: near/far tire pairs 61 px
                // and 58 px apart, against a 76 px target). Parking it off-screen is what
                // actually removes it — the parent is not clipped, so it simply becomes
                // unreachable. Note a focus-dimmed dot stays tappable: spec §7 requires tapping a
                // second hotspot while one is focused, and that dot is still where the user
                // expects it. The two fades look alike and deliberately differ here.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset { projector.tapOffsetOf(hotspot) }
                        .clip(CircleShape)
                        .hoverable(interactions)
                        .clickable(
                            interactionSource = interactions,
                            indication = null, // the alpha change IS the feedback
                            role = Role.Button,
                            onClickLabel = hotspot.label,
                        ) {
                            if (projector.isTappableNow(hotspot)) onHotspotTap(hotspot)
                        },
                )
            }
        }
    }
}

/**
 * The glyph each hotspot wears. Chosen so the four tires are the only repeated icon — every other
 * component is distinguishable at dot size without reading a label.
 *
 * `Album` for the brakes is not an arbitrary stand-in: it is a disc with a centre bore, which is
 * what a brake rotor looks like, and it stays legible at 17 dp where a caliper outline would not.
 */
private fun glyphFor(hotspot: Hotspot): ImageVector = when (hotspot) {
    Hotspot.BATTERY -> Icons.Filled.BatteryChargingFull
    Hotspot.MOTOR -> Icons.Filled.ElectricBolt
    Hotspot.TIRE_FL, Hotspot.TIRE_FR, Hotspot.TIRE_RL, Hotspot.TIRE_RR -> Icons.Filled.TireRepair
    Hotspot.BRAKES -> Icons.Filled.Album
    Hotspot.DOORS -> Icons.Filled.SensorDoor
}

/**
 * Single small canvas per dot, with the component glyph laid over it. All animated values are read
 * INSIDE the draw lambda — see the `pulse` comment in [HotspotOverlay] for why that matters.
 *
 * This draws the dot unconditionally; whether it should be visible at all is decided by the
 * caller's `graphicsLayer` alpha. In particular, dots whose component sits on the hidden side of
 * the car are faded to near-invisible from `CarRenderer.occlusionOf` — they are still drawn, just
 * barely, so the annotation layer reads as wrapping around the vehicle rather than stopping at
 * its silhouette.
 */
@Composable
private fun HotspotDot(
    hotspot: Hotspot,
    color: Color,
    hasSignal: Boolean,
    pulsing: Boolean,
    focused: Boolean,
    ringColor: Color,
    pulse: State<Float>,
) {
    // Which of black/white reads on this disc is a property of the severity colour, and the
    // severity palette differs between day and night — so it is measured, not chosen. Picking a
    // fixed foreground would leave the glyph nearly invisible on whichever theme has the lighter
    // fill (day `success` is the light one; see the contrast note in docs/05-diagnostics-status).
    val glyphColor = if (color.luminance() > 0.45f) Color.Black else Color.White

    Box(contentAlignment = Alignment.Center) {
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
            if (focused) {
                drawCircle(color = color, radius = r * 1.75f, style = Stroke(width = 2.dp.toPx()))
            }
        }

        // The glyph replaces the old bright centre dot. It says which component this is without a
        // label, which is the whole point of an annotation layer over a car the driver already
        // recognises. `contentDescription` is null because the 76 dp hit target behind it already
        // carries `onClickLabel = hotspot.label` — announcing both would read the name twice.
        Icon(
            imageVector = glyphFor(hotspot),
            contentDescription = null,
            tint = glyphColor.copy(alpha = if (hasSignal) 1f else 0.55f),
            modifier = Modifier.size(HotspotTokens.glyph),
        )
    }
}
