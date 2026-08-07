package com.motorguard.ivi.ui.dialer

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.data.ActiveCall
import com.motorguard.ivi.data.CallState
import com.motorguard.ivi.data.Contact
import com.motorguard.ivi.ui.theme.Semantic
import kotlinx.coroutines.delay

/**
 * Takes over the whole tab while a call is up. One job per element, oversized numerals,
 * controls on the 76 dp grid — this is the screen most likely to be used while moving.
 *
 * The dialing pulse animates scale and alpha only (README §1); the duration ticks in the
 * UI off `elapsedRealtime`, so the repository never emits once per second.
 */
@Composable
fun InCallScreen(vm: DialerViewModel, call: ActiveCall) {
    val ringing = call.state == CallState.RINGING
    val dialing = call.state == CallState.DIALING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Avatar(
            initials = call.name?.let { Contact(0, it, call.number).initials } ?: "#",
            pulsing = ringing || dialing,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = call.label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 42.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )

        if (call.name != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = call.number,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontSize = 17.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        StatusLine(call)
        Spacer(Modifier.height(36.dp))

        if (ringing) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RoundAction(
                    icon = Icons.Filled.CallEnd,
                    label = "Decline",
                    fill = Semantic.critical,
                    onClick = vm::endCall,
                )
                RoundAction(
                    icon = Icons.Filled.Call,
                    label = "Answer",
                    fill = Semantic.success,
                    onClick = vm.repo::answer,
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundAction(
                    icon = if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (call.muted) "Unmute" else "Mute",
                    active = call.muted,
                    onClick = { vm.repo.setMuted(!call.muted) },
                )
                RoundAction(
                    icon = Icons.Filled.Dialpad,
                    label = "Keypad",
                    active = vm.inCallKeypad,
                    enabled = call.connected,
                    onClick = { vm.inCallKeypad = !vm.inCallKeypad },
                )
                RoundAction(
                    icon = if (call.state == CallState.HOLDING) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    label = if (call.state == CallState.HOLDING) "Resume" else "Hold",
                    active = call.state == CallState.HOLDING,
                    enabled = call.connected,
                    onClick = { vm.repo.setOnHold(call.state != CallState.HOLDING) },
                )
                Spacer(Modifier.width(8.dp))
                RoundAction(
                    icon = Icons.Filled.CallEnd,
                    label = "End",
                    fill = Semantic.critical,
                    onClick = vm::endCall,
                )
            }
        }

        if (vm.inCallKeypad && call.connected) {
            Spacer(Modifier.height(28.dp))
            Dialpad(onPress = { vm.repo.sendDtmf(it) }, keySize = 76.dp)
        }
    }
}

@Composable
private fun StatusLine(call: ActiveCall) {
    val text = when (call.state) {
        CallState.DIALING -> "Calling…"
        CallState.RINGING -> "Incoming call"
        CallState.HOLDING -> "On hold"
        CallState.ENDING -> "Call ended"
        CallState.ACTIVE -> durationText(call.startedAtElapsedMs)
    }
    Text(
        text = text,
        color = if (call.state == CallState.ACTIVE) Semantic.success else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        fontSize = 24.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** Ticks once a second while composed; nothing below the UI layer knows about seconds. */
@Composable
private fun durationText(startedAtElapsedMs: Long): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtElapsedMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    if (startedAtElapsedMs == 0L) return "00:00"
    val total = ((now - startedAtElapsedMs) / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun Avatar(initials: String, pulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "call-pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = if (pulsing) 0.05f else 0.45f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "alpha",
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .scale(scale)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RoundAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    fill: Color? = null,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val background = when {
        fill != null -> fill
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        else -> Color.White.copy(alpha = 0.07f)
    }
    val tint = when {
        fill != null -> Semantic.onSemantic
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onBackground
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(if (enabled) background else background.copy(alpha = 0.25f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            fontSize = 13.sp,
        )
    }
}
