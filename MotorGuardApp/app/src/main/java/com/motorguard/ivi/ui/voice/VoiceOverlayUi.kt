package com.motorguard.ivi.ui.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
enum class VoiceRoute(val label: String) {
    MEDIA("Play music"),
    NAV("Navigate"),
    DIAGNOSTICS("Vehicle status"),
    SETTINGS("Settings"),
    PHONE("Call"),
}

// The overlay is always dark — it floats over arbitrary content, so it pins to the
// night palette for contrast rather than following Day/Night.
private val Panel = Tokens.Night.panel
private val OnPanel = Tokens.Night.onBase
private val OnPanelDim = Tokens.Night.onBaseDim
private val Accent = Tokens.Night.accent

/**
 * Bottom listen bar, Google-Assistant style. Transform/opacity animation only —
 * no blur or shadow animation (RPi 5 perf budget, see README §1).
 */
@Composable
fun VoiceOverlay(
    model: VoiceUiModel,
    onChip: (VoiceRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {

        // Scrim: tapping outside the bar dismisses immediately (docs/07-voice.md).
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Panel.copy(alpha = 0.96f))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VoiceOrb(model.state, model.level)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = statusLabel(model.state),
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = displayText(model),
                        color = if (model.reply.isNotBlank()) OnPanel else OnPanelDim,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        maxLines = 3,
                    )
                }
                if (model.state == VoiceState.LISTENING) {
                    Spacer(Modifier.width(16.dp))
                    Waveform(model.level)
                }
            }

            // Chips only while idle-ish: they'd fight the transcript mid-utterance.
            if (model.state == VoiceState.LISTENING && model.transcript.isBlank()) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VoiceRoute.entries.forEach { route ->
                        Chip(route.label) { onChip(route) }
                    }
                }
            }
        }
    }
}

private fun statusLabel(state: VoiceState) = when (state) {
    VoiceState.IDLE -> ""
    VoiceState.LISTENING -> "LISTENING"
    VoiceState.THINKING -> "THINKING"
    VoiceState.SPEAKING -> "MOTOR GUARD"
}

private fun displayText(m: VoiceUiModel) = when {
    m.reply.isNotBlank() -> m.reply
    m.transcript.isNotBlank() -> m.transcript
    m.state == VoiceState.LISTENING -> "Listening…"
    m.state == VoiceState.THINKING -> "One moment…"
    else -> ""
}

/** Pulsing orb; scale/alpha only. */
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
        modifier = Modifier
            .size(56.dp)
            .scale(scale)
            .clip(RoundedCornerShape(28.dp))
            .background(Accent.copy(alpha = if (state == VoiceState.IDLE) 0.25f else 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Voice assistant",
            tint = Tokens.Night.base,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** Five bars reacting to mic RMS. Height changes only. */
@Composable
private fun Waveform(level: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val weights = listOf(0.5f, 0.8f, 1f, 0.7f, 0.45f)
        weights.forEach { w ->
            val h = (8f + level * 34f * w).coerceIn(6f, 42f)
            Box(
                Modifier
                    .width(5.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Accent.copy(alpha = 0.85f)),
            )
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(text = label, color = OnPanel, fontSize = 15.sp)
    }
}
