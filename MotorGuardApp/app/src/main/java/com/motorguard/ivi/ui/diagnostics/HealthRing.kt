package com.motorguard.ivi.ui.diagnostics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.theme.SemanticColors

/**
 * Aggregate health score ring. Pure display, no tap (spec: health ring has no
 * action). 0–100, animated over 600 ms.
 */
@Composable
fun HealthRing(
    score: Int?,          // null = no usable live data yet
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val ringColor = when {
        score == null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        score >= 90 -> SemanticColors.success
        score >= 70 -> SemanticColors.caution
        else -> SemanticColors.critical
    }

    val fraction = (score ?: 0) / 100f
    val animatedSweep by animateFloatAsState(
        targetValue = fraction * 360f,
        animationSpec = tween(600),
        label = "healthRingSweep",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(width = this.size.minDimension * 0.11f, cap = StrokeCap.Round)
            drawCircle(color = trackColor, style = stroke)
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = animatedSweep,
                useCenter = false,
                style = stroke,
            )
        }
        Text(
            text = score?.toString() ?: "--",
            fontSize = (size.value * 0.30f).sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
