package com.motorguard.ivi.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The glass surface the whole design language is built on: translucent fill, hairline border,
 * a one-pixel highlight along the top edge. Mirrors the `--glass` / `--glass-brd` / `--hi`
 * variables from MeowScreen.dc.html.
 *
 * On purpose, it does **not** blur what is behind it. Compose has no backdrop-blur primitive
 * (`Modifier.blur` blurs the composable's own content, not the map underneath), and the
 * README's perf budget allows exactly one blurred backdrop per screen anyway. Over a dark map
 * the translucent fill plus the border reads as glass on its own — and it costs nothing.
 *
 * @param soft use the lighter `--glass2` fill, for containers behind other glass.
 * @param shimmer sweeps a highlight across the panel. Reserved for
 *        [com.motorguard.ivi.ui.nav.AnimationLevel.SHOWCASE] — it repeats forever, so do not
 *        enable it on a panel that is permanently on screen.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    padding: PaddingValues = PaddingValues(horizontal = 26.dp, vertical = 18.dp),
    soft: Boolean = false,
    shimmer: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MotorGuard.colors
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (soft) colors.glassSoft else colors.glass)
            .border(1.dp, colors.glassBorder, shape)
            .topHighlight(colors.highlight)
            .then(if (shimmer) Modifier.shimmerSweep(colors.highlight) else Modifier)
            .padding(padding),
        content = content,
    )
}

/**
 * A square glass control — the 52 dp chips in the ETA bar, the 70 dp buttons on the map.
 * Uses the `--chip` fill, which is opaque enough to stay legible over bright map features.
 */
@Composable
fun GlassChip(
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(17.dp),
    fill: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MotorGuard.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(fill ?: colors.chip)
            .border(borderWidth, borderColor ?: colors.glassBorder, shape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** The `inset 0 1px 0 var(--hi)` from the CSS: one bright line along the top edge. */
private fun Modifier.topHighlight(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(color, Color.Transparent),
            startY = 0f,
            endY = 2f,
        ),
        size = Size(size.width, 2f),
    )
}

/** Diagonal highlight sweep. Transform/opacity only — a moving gradient, never a blur. */
@Composable
private fun Modifier.shimmerSweep(color: Color): Modifier {
    val transition = rememberInfiniteTransition(label = "glass-shimmer")
    val progress by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "glass-shimmer-progress",
    )
    return drawWithContent {
        drawContent()
        val sweepWidth = size.width * 0.35f
        val x = size.width * progress
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, color, Color.Transparent),
                start = Offset(x - sweepWidth, 0f),
                end = Offset(x + sweepWidth, size.height),
            ),
        )
    }
}
