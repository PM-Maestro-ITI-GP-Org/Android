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
import com.motorguard.ivi.ui.nav.map.VehicleArrow
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The car under the **chase camera**: an accent arrowhead with a soft breathing halo.
 *
 * This is the screen-anchored variant, and it is only correct while the camera is following the
 * car — then the puck genuinely does not move, the world slides beneath it, and the pulse is
 * free: a scale and an alpha on a composable the map knows nothing about.
 *
 * Everywhere else — route preview, the whole-trip overview, idle — the car has to be *geo*
 * anchored, because the camera is framing something other than the car and its position on
 * screen is wherever its coordinates land. That case is `MapOverlay.vehicle`, drawn by the map
 * itself from the same [VehicleArrow] geometry.
 *
 * @param rotationDegrees heading to point at. Zero under the chase camera, because the map is
 *        rotated to the heading and up *is* forward.
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

/**
 * An arrowhead pointing up, built from the shared outline in [VehicleArrow] — the same geometry
 * the map's own marker uses, so the car cannot be one shape in guidance and another in overview.
 */
private fun arrowPath(centre: Offset, size: Float): Path = Path().apply {
    VehicleArrow.outline.forEachIndexed { index, (x, y) ->
        val px = centre.x + x * size
        val py = centre.y + y * size
        if (index == 0) moveTo(px, py) else lineTo(px, py)
    }
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
private val ARROW_SCALE = VehicleArrow.ARROW_SCALE
