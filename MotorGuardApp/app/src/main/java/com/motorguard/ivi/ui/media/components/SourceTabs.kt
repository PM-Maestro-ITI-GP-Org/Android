package com.motorguard.ivi.ui.media.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.data.media.MediaLibrarySource
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The segmented source switcher from docs/04-media.md.
 *
 * Unavailable sources stay **visible and tappable** rather than being hidden or greyed out —
 * selecting the USB tab with no stick in is how a driver finds out they need to plug one in, and
 * the tab's empty state is the place that tells them. Hiding it would make the feature look like
 * it does not exist.
 */
@Composable
fun SourceTabs(
    sources: List<MediaLibrarySource>,
    active: MediaSourceId,
    availableIds: Set<MediaSourceId>,
    onSelect: (MediaSourceId) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        padding = androidx.compose.foundation.layout.PaddingValues(6.dp),
        soft = true,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEach { source ->
                SourceTab(
                    label = source.label,
                    icon = sourceIcon(source.id),
                    selected = source.id == active,
                    available = source.id in availableIds,
                    onClick = { onSelect(source.id) },
                )
            }
        }
    }
}

@Composable
private fun SourceTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    available: Boolean,
    onClick: () -> Unit,
) {
    val colors = MotorGuard.colors

    val background by animateColorAsState(
        targetValue = if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = NavMotion.settle(),
        label = "source-tab-bg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            selected -> colors.accent
            else -> colors.onBaseDim
        },
        animationSpec = NavMotion.settle(),
        label = "source-tab-tint",
    )

    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(22.dp)
                    // Dim the glyph rather than the whole tab, so the label stays readable.
                    .alpha(if (available) 1f else 0.45f),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) tint else MaterialTheme.colorScheme.onSurface,
        )
        if (!available) {
            Spacer(Modifier.width(8.dp))
            // A small dot beats the word "unavailable" at a glance and survives translation.
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.onBaseDim),
            )
        }
    }
}

private fun sourceIcon(id: MediaSourceId): ImageVector = when (id) {
    MediaSourceId.LOCAL -> Icons.Filled.LibraryMusic
    MediaSourceId.USB -> Icons.Filled.Usb
    MediaSourceId.BLUETOOTH -> Icons.Filled.Bluetooth
    MediaSourceId.RADIO -> Icons.Filled.Radio
    MediaSourceId.VIDEO -> Icons.Filled.Movie
}
