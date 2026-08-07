package com.motorguard.ivi.ui.diagnostics

import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.R
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import com.motorguard.ivi.data.vehicle.api.isOfflineOrLoading
import com.motorguard.ivi.ui.theme.SemanticColors

/**
 * The left pane: top-down car + tappable hotspot dots.
 *
 * - 250 ms fly-to/zoom per spec ([DiagnosticsViewModel.FLY_TO_MS]); non-focused
 *   dots fade with [DiagnosticsViewModel.FADE_OTHERS_MS].
 * - The ViewModel owns WHICH hotspot is focused; this layer only owns the
 *   animatables and the visual mapping.
 * - Dots are 76 dp touch targets (docs requirement) around a 34 dp visual; they ride
 *   the same zoom transform as the image so they track the car during the fly-to.
 */
@Composable
fun CarScene(vm: DiagnosticsViewModel, modifier: Modifier = Modifier) {
    val focused by vm.focused.collectAsStateWithLifecycle()
    val renderer = vm.renderer

    // One animatable driving the zoom; target derived from current focus so
    // fast hotspot switches animate smoothly (don't snap) between anchors.
    val zoomT = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        zoomT.animateTo(
            targetValue = if (focused != null) 1f else 0f,
            animationSpec = tween(DiagnosticsViewModel.FLY_TO_MS, easing = FastOutSlowInEasing),
        )
    }

    BoxWithConstraints(modifier) {
        val containerPx = IntSize(constraints.maxWidth, constraints.maxHeight)
        val carRect = remember(containerPx) { fitContentRect(containerPx, 200f / 420f) }

        // The image + zoom transform (single layer, transform/opacity only per the
        // RPi5 perf budget).
        CarImageLayer(
            renderer = renderer,
            progress = zoomT.value,
            focused = focused,
            containerPx = containerPx,
        )

        // Hotspot dots, in their own layer so the background-tap detector can sit
        // beneath them.
        DotsLayer(
            vm = vm,
            renderer = renderer,
            carRect = carRect,
            progress = zoomT.value,
            focused = focused,
        )

        // Full-pane "tap outside to zoom out" — UNDER the dots so dots win taps.
        if (focused != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { vm.clearFocus() },
                    ),
            )
        }
    }
}

@Composable
private fun CarImageLayer(
    renderer: TopDownCarRenderer,
    progress: Float,
    focused: Hotspot?,
    containerPx: IntSize,
) {
    val target: CarViewTarget = focused
        ?.let { h -> renderer.viewTargetFor(renderer.anchors.first { it.hotspot == h }) }
        ?: renderer.idleViewTarget

    val carRect = fitContentRect(containerPx, imageAspect = 200f / 420f)
    val anchorFrac = target.center
    val anchorPx = Offset(
        carRect.left + anchorFrac.x * carRect.width(),
        carRect.top + anchorFrac.y * carRect.height(),
    )
    val scale = lerp(1f, target.scale, progress)
    val tx = lerp(0f, containerPx.width / 2f - anchorPx.x, progress)
    val ty = lerp(0f, containerPx.height / 2f - anchorPx.y, progress)

    Image(
        painter = painterResource(R.drawable.car_topdown),
        contentDescription = "Vehicle top-down view",
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                transformOrigin = TransformOrigin(anchorFrac.x, anchorFrac.y)
                scaleX = scale
                scaleY = scale
                translationX = tx
                translationY = ty
            },
    )
}

@Composable
private fun DotsLayer(
    vm: DiagnosticsViewModel,
    renderer: TopDownCarRenderer,
    carRect: RectF,
    progress: Float,
    focused: Hotspot?,
) {
    val severities by vm.severities.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val motor by vm.motor.collectAsStateWithLifecycle()
    val brakes by vm.brakes.collectAsStateWithLifecycle()
    val tires by vm.tires.collectAsStateWithLifecycle()
    val doors by vm.doors.collectAsStateWithLifecycle()

    fun signalFor(h: Hotspot): SignalState<*> = when (h) {
        Hotspot.BATTERY -> battery
        Hotspot.MOTOR -> motor
        Hotspot.BRAKES -> brakes
        Hotspot.DOORS -> doors
        Hotspot.TIRE_FL -> tires.getOrElse(0) { SignalState.Offline }
        Hotspot.TIRE_FR -> tires.getOrElse(1) { SignalState.Offline }
        Hotspot.TIRE_RL -> tires.getOrElse(2) { SignalState.Offline }
        Hotspot.TIRE_RR -> tires.getOrElse(3) { SignalState.Offline }
    }

    renderer.anchors.forEach { anchor ->
        val sig = signalFor(anchor.hotspot)
        val severity = severities[anchor.hotspot]
        val isFocused = focused == anchor.hotspot
        val targetsAnotherFocused = focused != null && focused != anchor.hotspot

        val alpha by animateFloatAsState(
            targetValue = if (targetsAnotherFocused) 0f else 1f,
            animationSpec = tween(
                if (targetsAnotherFocused) DiagnosticsViewModel.FADE_OTHERS_MS else DiagnosticsViewModel.FLY_TO_MS,
                easing = FastOutSlowInEasing,
            ),
            label = "dot-alpha-${anchor.hotspot}",
        )

        HotspotDot(
            anchor = anchor,
            color = dotColor(sig, severity),
            alpha = alpha,
            carRect = carRect,
            progress = progress,
            isFocused = isFocused,
            onTap = { vm.focus(anchor.hotspot); vm.noteInteraction() },
        )
    }
}

@Composable
private fun dotColor(sig: SignalState<*>, severity: Severity?): Color = when {
    sig.isOfflineOrLoading -> SemanticColors.offline
    severity == Severity.OK -> SemanticColors.success
    severity == Severity.CAUTION -> SemanticColors.caution
    severity == Severity.CRITICAL -> SemanticColors.critical
    else -> SemanticColors.offline
}

@Composable
private fun HotspotDot(
    anchor: HotspotAnchor,
    color: Color,
    alpha: Float,
    carRect: RectF,
    progress: Float,
    isFocused: Boolean,
    onTap: () -> Unit,
) {
    val density = LocalDensity.current

    // Car-relative screen position (fractions → container pixels).
    val anchorPx = Offset(
        carRect.left + anchor.fraction.x * carRect.width(),
        carRect.top + anchor.fraction.y * carRect.height(),
    )

    // Ride the same zoom transform as the image so the dot tracks the car during
    // the fly-to: scale around the image center, then translate toward the anchor.
    val targetScale = 2.7f
    val containerCenterOffset = anchorPx - Offset(carRect.centerX(), carRect.centerY())

    val scale = lerp(1f, targetScale, progress)
    val tx = lerp(0f, -containerCenterOffset.x, progress)
    val ty = lerp(0f, -containerCenterOffset.y, progress)

    Box(
        modifier = Modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                translationX = tx
                translationY = ty
                // scaleX/Y intentionally NOT applied here: the dot's pixel size
                // stays constant (76 dp) even under zoom; only its position tracks.
            }
            .offset(
                x = with(density) { (anchorPx.x - 38.dp.toPx()).toDp() },
                y = with(density) { (anchorPx.y - 38.dp.toPx()).toDp() },
            )
            .size(76.dp)
            .alpha(alpha)
            .semantics {
                contentDescription = anchor.hotspot.label
                role = Role.Button
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 38.dp),
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // visual dot
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = anchor.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        // focus ring
        if (isFocused) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .border(width = 2.5.dp, color = Color.White, shape = CircleShape),
            )
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
