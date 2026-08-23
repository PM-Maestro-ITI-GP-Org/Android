package com.motorguard.ivi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.media.components.VolumeBar
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * A volume panel that slides out from the left edge, reachable from any tab — not folded into
 * Media's own volume row, which only exists while the driver is already on that screen.
 *
 * Tap-driven rather than an edge-swipe: several screens already claim a horizontal drag of their
 * own at the left edge of the content area (the Nav map pans, the Diagnostics car stage orbits),
 * and a global swipe detector layered over all of them would be fighting one gesture recognizer
 * against another rather than adding a new control. The handle is a small, fixed, always-visible
 * tab instead — one deliberate tap, no ambiguity with what is underneath it.
 */
@Composable
fun VolumeDrawer(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        // Scrim + tap-outside-to-dismiss, exactly like the voice overlay's own scrim.
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { open = false },
                    ),
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(180)) { -it } + fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            GlassCard(
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                padding = PaddingValues(horizontal = 26.dp, vertical = 28.dp),
                modifier = Modifier
                    .width(320.dp)
                    // Absorbs taps so they don't fall through to the scrim behind the panel.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = "Volume",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(22.dp))
                    VolumeBar(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // The handle: fixed to the edge, vertically centered, on top of everything else.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(width = 26.dp, height = 88.dp)
                .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                .background(MotorGuard.colors.glass)
                .clickable { open = !open },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (open) Icons.Filled.ChevronRight else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (open) "Close volume" else "Open volume",
                tint = MotorGuard.colors.accent,
                modifier = Modifier
                    .size(if (open) 20.dp else 18.dp)
                    .rotate(if (open) 180f else 0f),
            )
        }
    }
}
