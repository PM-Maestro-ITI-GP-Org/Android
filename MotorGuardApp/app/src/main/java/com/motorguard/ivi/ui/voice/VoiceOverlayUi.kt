package com.motorguard.ivi.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
                    // Light enough to read the screen underneath, dark enough that the
                    // panel separates from it. The session window is transparent now, so
                    // this is the only thing between the driver and whatever tab they were
                    // on — heavier values here simply reproduce the black takeover this
                    // replaced. Darkest at the edges, clearest behind the panel.
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.62f)),
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
                    // Hugs its content instead of claiming a fixed 72% of the screen.
                    // The old fixed height was sized for the busiest state, so every
                    // quieter one — a spoken reply, which is most of the session — left
                    // the orb marooned in a large empty box. animateContentSize keeps the
                    // card from snapping as states add and remove rows.
                    // The header spans the card, so this max is what the card actually
                    // takes: 980 made a letterbox strip nearly the full width of the dash
                    // with an orb adrift in it. 660 keeps the card close to square in its
                    // quiet states and still gives a spoken reply a comfortable measure.
                    .widthIn(min = 520.dp, max = 660.dp)
                    .wrapContentHeight()
                    .animateContentSize(tween(220))
                    .clip(RoundedCornerShape(36.dp))
                    // Denser than it was. The window used to sit on an opaque black
                    // takeover, where 0.58/0.42 was plenty; now the map or the album art
                    // is right behind the panel, and reply text at this size has to stay
                    // readable over whatever happens to be there. Still translucent enough
                    // to read as glass rather than a slab.
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Panel.copy(alpha = 0.90f), Panel.copy(alpha = 0.80f)),
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
                    .padding(horizontal = 44.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(model.state)

                Spacer(Modifier.height(22.dp))
                VoiceOrb(model.state, model.level)

                if (model.state == VoiceState.LISTENING) {
                    Spacer(Modifier.height(20.dp))
                    Waveform(model.level)
                }

                // What it heard, then what it answered — both, not one replacing the
                // other. Seeing your own words is how you know a wrong answer came from
                // a misheard question rather than a misunderstood one.
                if (model.transcript.isNotBlank()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "“${model.transcript}”",
                        color = OnPanelDim,
                        fontSize = 19.sp,
                        lineHeight = 26.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }

                if (model.reply.isNotBlank()) {
                    Spacer(Modifier.height(if (model.transcript.isNotBlank()) 14.dp else 24.dp))
                    Text(
                        text = model.reply,
                        color = OnPanel,
                        fontSize = 28.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }

                // Offered until there is something to say — they are suggestions for a
                // driver who summoned the assistant and then hesitated, so they belong
                // while it is waiting, not once it is answering.
                if (model.state == VoiceState.LISTENING && model.transcript.isBlank()) {
                    Spacer(Modifier.height(26.dp))
                    // Wraps. A single Row fitted four of the five chips inside the card
                    // and pushed "Call" past the edge, where it was clipped and could not
                    // be tapped — an offered action the driver could not reach.
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        VoiceRoute.entries.forEach { route ->
                            ActionChip(route.icon, route.label) { onChip(route) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Name on the left, what it is doing on the right.
 *
 * The orb's animation says *something* is happening but not which thing — a pulse while
 * thinking and a pulse while speaking look alike at a glance, and a driver looking up for
 * half a second should not have to decide whether to keep talking. One word settles it.
 */
@Composable
private fun Header(state: VoiceState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (state == VoiceState.LISTENING) Accent2 else Accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "VEGA",
            color = OnPanel,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = when (state) {
                VoiceState.LISTENING -> "Listening"
                VoiceState.THINKING -> "Thinking"
                VoiceState.SPEAKING -> "Speaking"
                VoiceState.IDLE -> ""
            },
            color = OnPanelDim,
            fontSize = 14.sp,
            letterSpacing = 0.5.sp,
        )
    }
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

/**
 * Quick action, icon *and* label.
 *
 * These were icon-only discs with the words hidden in contentDescription, which asks a
 * driver to decode five glyphs to find out what they may say. The point of offering
 * suggestions is that they can be read, so the label is on screen.
 */
@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = OnPanel,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
