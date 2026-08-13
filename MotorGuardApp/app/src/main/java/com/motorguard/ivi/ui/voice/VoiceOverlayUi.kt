package com.motorguard.ivi.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.theme.Tokens

/** The four states from docs/07-voice.md. */
enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * What the overlay renders. Held by [VoiceOverlaySession] as Compose state, so a
 * recognizer callback simply assigns and the UI follows.
 */
data class VoiceUiModel(
    val state: VoiceState = VoiceState.IDLE,
    val transcript: String = "",
    val reply: String = "",
    /** 0f..1f mic level, drives the waveform while listening. */
    val level: Float = 0f,
)

/** Quick-action chips → which tab the intent routes to. */
enum class VoiceRoute(val label: String, val icon: ImageVector) {
    MEDIA("Play music", Icons.Filled.MusicNote),
    NAV("Navigate", Icons.Filled.Navigation),
    DIAGNOSTICS("Vehicle status", Icons.Filled.DirectionsCar),
    SETTINGS("Settings", Icons.Filled.Settings),
    PHONE("Call", Icons.Filled.Phone),
}

// The overlay is always dark — it floats over arbitrary content, so it pins to the
// night palette for contrast rather than following Day/Night.
private val Panel = Tokens.Night.panel
private val OnPanel = Tokens.Night.onBase
private val OnPanelDim = Tokens.Night.onBaseDim
private val Accent = Tokens.Night.accent
private val Accent2 = Tokens.Night.accent2

/**
 * Semi-transparent panel over roughly two-thirds of the screen, centered — replaces
 * the earlier bottom listen-bar so the assistant reads as a clear takeover of the
 * screen rather than a small strip at the edge. Scale/fade/alpha animation only —
 * no animated blur or shadow (RPi 5 perf budget, see README §1); the depth here comes
 * from static gradients and a static elevation shadow instead.
 */
@Composable
fun VoiceOverlay(
    model: VoiceUiModel,
    onChip: (VoiceRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    val visible = model.state != VoiceState.IDLE

    Box(Modifier.fillMaxSize()) {

        // Scrim: tapping outside the panel dismisses immediately (docs/07-voice.md).
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.42f)),
                            radius = 1600f,
                        ),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            modifier = Modifier.align(Alignment.Center),
            visible = visible,
            enter = fadeIn(tween(240)) + scaleIn(initialScale = 0.90f, animationSpec = tween(240)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.94f, animationSpec = tween(160)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .fillMaxHeight(0.72f)
                    .clip(RoundedCornerShape(36.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Panel.copy(alpha = 0.58f), Panel.copy(alpha = 0.42f)),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(Accent.copy(alpha = 0.38f), Color.White.copy(alpha = 0.10f)),
                        ),
                        shape = RoundedCornerShape(36.dp),
                    )
                    // Absorbs taps on the panel itself so they don't fall through to
                    // the full-screen scrim behind it and dismiss the overlay.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 36.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                VoiceOrb(model.state, model.level)

                // Only real content gets text — the orb's animation alone carries
                // "listening" / "thinking", so no placeholder copy underneath it.
                val text = displayText(model)
                if (text.isNotEmpty()) {
                    Spacer(Modifier.height(26.dp))
                    Text(
                        text = text,
                        color = OnPanel,
                        fontSize = 28.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 4,
                        textAlign = TextAlign.Center,
                    )
                }

                if (model.state == VoiceState.LISTENING) {
                    Spacer(Modifier.height(26.dp))
                    Waveform(model.level)
                }

                // Chips only while idle-ish: they'd fight the transcript mid-utterance.
                if (model.state == VoiceState.LISTENING && model.transcript.isBlank()) {
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        VoiceRoute.entries.forEach { route ->
                            IconChip(route.icon, route.label) { onChip(route) }
                        }
                    }
                }
            }
        }
    }
}

/** Only real content is ever shown as text — no state labels, no placeholder copy. */
private fun displayText(m: VoiceUiModel) = when {
    m.reply.isNotBlank() -> m.reply
    m.transcript.isNotBlank() -> m.transcript
    else -> ""
}

/** Pulsing orb with a soft static glow behind it; scale/alpha animation only. */
@Composable
private fun VoiceOrb(state: VoiceState, level: Float) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == VoiceState.THINKING) 700 else 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val scale = when (state) {
        VoiceState.LISTENING -> pulse + level * 0.12f
        VoiceState.THINKING, VoiceState.SPEAKING -> pulse
        VoiceState.IDLE -> 1f
    }

    Box(
        modifier = Modifier.size(172.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Soft glow: a static radial gradient, not a RenderEffect blur — cheap to draw.
        Box(
            modifier = Modifier
                .size(172.dp)
                .scale(scale)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Accent.copy(alpha = 0.30f), Color.Transparent),
                    ),
                    shape = CircleShape,
                ),
        )

        // Sonar pings while listening: two rings, staggered, expanding and fading.
        // Scale/alpha only, drawn oversized so they can grow past the glow without
        // nudging layout — the panel's own clip trims them once they're basically gone.
        if (state == VoiceState.LISTENING) {
            SonarRing(delayMs = 0)
            SonarRing(delayMs = 900)
        }

        // Rotating gradient arc while thinking — a native "processing" spinner.
        if (state == VoiceState.THINKING) {
            val spin = rememberInfiniteTransition(label = "spin")
            val angle by spin.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "angle",
            )
            Canvas(
                modifier = Modifier
                    .size(142.dp)
                    .rotate(angle),
            ) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, Accent2, Accent, Color.Transparent),
                    ),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(112.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Accent2.copy(alpha = if (state == VoiceState.IDLE) 0.25f else 0.95f),
                            Accent.copy(alpha = if (state == VoiceState.IDLE) 0.25f else 0.95f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Voice assistant",
                tint = Tokens.Night.base,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

/** One expanding, fading ring — call twice with a staggered delay for a sonar-ping pair. */
@Composable
private fun SonarRing(delayMs: Int) {
    val transition = rememberInfiniteTransition(label = "sonar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            initialStartOffset = StartOffset(delayMs, StartOffsetType.Delay),
        ),
        label = "progress",
    )
    Box(
        modifier = Modifier
            .size(162.dp)
            .scale(0.5f + progress * 1.05f)
            .border(2.5.dp, Accent2.copy(alpha = (1f - progress) * 0.85f), CircleShape),
    )
}

/** Five gradient bars reacting to mic RMS. Height changes only. */
@Composable
private fun Waveform(level: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val weights = listOf(0.5f, 0.8f, 1f, 0.7f, 0.45f)
        weights.forEach { w ->
            val h = (14f + level * 54f * w).coerceIn(10f, 68f)
            Box(
                Modifier
                    .width(8.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.verticalGradient(colors = listOf(Accent2, Accent))),
            )
        }
    }
}

/** Icon-only quick action — the label lives in contentDescription, not on screen. */
@Composable
private fun IconChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Accent,
            modifier = Modifier.size(22.dp),
        )
    }
}
