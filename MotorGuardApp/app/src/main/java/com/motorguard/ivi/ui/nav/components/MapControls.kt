package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.ui.components.GlassChip
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * Re-centre on the car. Highlighted while the camera is *not* following, which is the only time
 * the button does anything — an always-lit control teaches nothing.
 */
@Composable
fun RecenterButton(
    following: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = NavMotion.swap(),
        label = "recenter-press",
    )

    GlassChip(
        size = 70.dp,
        shape = RoundedCornerShape(22.dp),
        fill = if (following) colors.glass else colors.accent.copy(alpha = 0.22f),
        borderColor = if (following) colors.glassBorder else colors.accent,
        modifier = modifier
            .scale(scale)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Filled.MyLocation,
            contentDescription = "Re-centre on vehicle",
            tint = if (following) MaterialTheme.colorScheme.onSurface else colors.accent,
            modifier = Modifier.size(28.dp),
        )
    }
}
