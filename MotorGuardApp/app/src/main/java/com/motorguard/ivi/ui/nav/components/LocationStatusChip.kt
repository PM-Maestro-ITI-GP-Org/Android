package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * Shown while the GNSS source has no fix yet — either because the permission was refused, or
 * because the receiver simply has not locked on.
 *
 * The important part is the second button. A driver (or a student demoing at a desk with no sky
 * and no receiver) should not have to work out that the screen is fine and the *hardware* is the
 * problem, nor edit `NavConfig` and rebuild to get moving. One tap switches to the simulator.
 */
@Composable
fun LocationStatusChip(
    permissionGranted: Boolean,
    onGrantPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors

    // Slow breath on the icon: says "still trying" without a spinner's urgency.
    val transition = rememberInfiniteTransition(label = "gps-wait")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gps-wait-alpha",
    )

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (permissionGranted) Icons.Filled.GpsFixed else Icons.Filled.GpsOff,
                contentDescription = null,
                tint = if (permissionGranted) colors.accent else colors.caution,
                modifier = Modifier
                    .size(22.dp)
                    .alpha(if (permissionGranted) pulse else 1f),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = if (permissionGranted) {
                    "Waiting for GPS fix…"
                } else {
                    "Location permission needed"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (!permissionGranted) {
                Spacer(Modifier.width(20.dp))
                ChipAction(label = "Grant", primary = true, onClick = onGrantPermission)
            }
        }
    }
}

@Composable
private fun ChipAction(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = MotorGuard.colors
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (primary) colors.accent.copy(alpha = 0.20f) else colors.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) colors.accent else colors.onBaseDim,
        )
    }
}
