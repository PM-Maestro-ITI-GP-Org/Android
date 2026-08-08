package com.motorguard.ivi.ui.diagnostics.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.theme.SemanticColors

/**
 * Aggregate vehicle health as an animated ring.
 *
 * Per the component spec the ring has no tap action — it is a summary, not a control. The
 * long-press is a development affordance only, and is the sole way into the fake-data panel.
 *
 * [score] of null means no signal has produced a severity yet, and the ring says so rather than
 * drawing a zero: a health score of 0 and "we do not know yet" are opposite claims.
 */
@Composable
internal fun HealthRing(
    score: Int?,
    worst: Severity?,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The sweep animates from wherever it was, so a severity change reads as the score moving
    // rather than the ring being redrawn.
    val sweep by animateFloatAsState(
        targetValue = (score ?: 0) / 100f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "healthSweep",
    )
    val ringColor = SemanticColors.forSeverity(worst)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    GlassCard(
        modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = track,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (sweep > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = 360f * sweep,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Text(
                    text = score?.toString() ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.width(24.dp))

            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = "Vehicle health",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // Every branch is a statement about the DATA, never an unsupported claim about
                    // the vehicle: with no signal the honest answer is that there is no signal.
                    text = when {
                        score == null -> "Waiting for telemetry"
                        worst == Severity.CRITICAL -> "Critical fault present"
                        worst == Severity.CAUTION -> "Needs attention"
                        else -> "All monitored systems nominal"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
            }
        }
    }
}

/** Screen-reader description for the ring, kept beside the visual copy it mirrors. */
internal fun healthRingDescription(score: Int?, worst: Severity?): String = when {
    score == null -> "Vehicle health unknown, waiting for telemetry"
    worst == Severity.CRITICAL -> "Vehicle health $score of 100, critical fault present"
    worst == Severity.CAUTION -> "Vehicle health $score of 100, needs attention"
    else -> "Vehicle health $score of 100, all monitored systems nominal"
}

/** Applies [healthRingDescription] as the accessibility label for a ring subtree. */
internal fun Modifier.healthRingSemantics(score: Int?, worst: Severity?): Modifier =
    semantics { contentDescription = healthRingDescription(score, worst) }
