package com.motorguard.ivi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.MainActivity.Tab

private data class RailItem(
    val tab: Tab,
    val label: String,
    val on: ImageVector,
    val off: ImageVector,
)

private val items = listOf(
    RailItem(Tab.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    RailItem(Tab.MEDIA, "Media", Icons.Filled.MusicNote, Icons.Outlined.MusicNote),
    RailItem(Tab.NAV, "Navigation", Icons.Filled.Navigation, Icons.Outlined.Navigation),
    RailItem(Tab.DIAGNOSTICS, "Diagnostics", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar),
    RailItem(Tab.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * Fixed left rail. One selected item at a time (rounded highlight), a voice/mic button,
 * and the MOTOR GUARD wordmark pinned at the bottom. See docs/01-navrail.md.
 */
@Composable
fun NavRail(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    onVoice: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(92.dp)
            .background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        items.forEach { item ->
            RailButton(
                icon = if (item.tab == selected) item.on else item.off,
                label = item.label,
                selected = item.tab == selected,
                onClick = { onSelect(item.tab) },
            )
        }
        // Voice: not a tab — triggers the assistant overlay (future work).
        RailButton(
            icon = Icons.Filled.Mic,
            label = "Voice",
            selected = false,
            onClick = onVoice,
        )

        Spacer(Modifier.weight(1f))
        BrandMark()
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val activeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .size(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) activeBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            },
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun BrandMark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = "Motor Guard",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "MOTOR\nGUARD",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            lineHeight = 11.sp,
        )
    }
}
