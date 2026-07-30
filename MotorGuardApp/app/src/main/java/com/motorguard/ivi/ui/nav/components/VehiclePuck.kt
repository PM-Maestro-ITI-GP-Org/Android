package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The car on the map: an accent arrowhead with a soft breathing halo.
 *
 * Drawn in Compose rather than as a map symbol layer, for two reasons. It is identical for both
 * map backends, and while guiding it never moves on screen — the camera follows the car, so the
 * puck sits at a fixed point and only the world slides beneath it. That makes it free to
 * animate: the pulse is a scale and an alpha on a composable the map knows nothing about.
 *
 * @param rotationDegrees heading to point at. Zero while guiding (the map itself is rotated to
 *        the heading, so up *is* forward); the real heading in the north-up overview.
 */
@Composable
fun VehiclePuck(
    modifier: Modifier = Modifier,
    rotationDegrees: Float = 0f,
    moving: Boolean = true,
) {
    val colors = MotorGuard.colors

    val pulse = rememberPulse(active = moving && NavMotion.puckPulse)

    Canvas(modifier = modifier.size(PUCK_BOX_DP.dp)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f

        // Halo: expands and fades out, so it reads as a sonar ping rather than a glow.
        if (pulse > 0f) {
            drawCircle(
                color = colors.accent,
                radius = radius * (0.45f + 0.55f * pulse),
                center = centre,
                alpha = 0.22f * (1f - pulse),
            )
        }
        // Static inner halo keeps the puck anchored even when the pulse is off.
        drawCircle(color = colors.accent, radius = radius * 0.42f, center = centre, alpha = 0.16f)

        rotate(degrees = rotationDegrees, pivot = centre) {
            val arrow = arrowPath(centre, radius * ARROW_SCALE)
            // Base-coloured outline first: it separates the arrow from a busy map underneath.
            drawPath(
                path = arrow,
                color = if (colors.isDark) colors.glassBorder else colors.onBaseDim,
                style = Stroke(width = radius * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(path = arrow, color = colors.accent)
        }
    }
}

/** An arrowhead pointing up: sharp nose, swept-back tips, notched tail. */
private fun arrowPath(centre: Offset, size: Float): Path = Path().apply {
    moveTo(centre.x, centre.y - size)
    lineTo(centre.x + size * 0.72f, centre.y + size * 0.78f)
    lineTo(centre.x, centre.y + size * 0.34f)
    lineTo(centre.x - size * 0.72f, centre.y + size * 0.78f)
    close()
}

/** 0 → 1 ping cycle, or a flat 0 when the pulse is switched off. */
@Composable
private fun rememberPulse(active: Boolean): Float {
    if (!active) return 0f
    val transition = rememberInfiniteTransition(label = "puck-pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "puck-pulse-progress",
    )
    return progress
}

private const val PUCK_BOX_DP = 76
private const val ARROW_SCALE = 0.5f
