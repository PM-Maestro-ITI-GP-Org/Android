package com.motorguard.ivi.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.ElectricCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
    RailItem(Tab.DIAGNOSTICS, "Diagnostics", Icons.Filled.ElectricCar, Icons.Outlined.ElectricCar),
    RailItem(Tab.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * Fixed left rail. Exactly one item selected; tapping calls [onSelect]. The brand mark
 * is pinned to the bottom. See docs/01-navrail.md for the full spec.
 */
@Composable
fun NavRail(
    selected: Tab,
    onSelect: (Tab) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Spacer(Modifier.weight(1f))
        items.forEach { item ->
            val isSelected = item.tab == selected
            NavigationRailItem(
                selected = isSelected,
                onClick = { onSelect(item.tab) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) item.on else item.off,
                        contentDescription = item.label,
                        modifier = Modifier.size(34.dp),
                    )
                },
                label = { Text(item.label) },
                alwaysShowLabel = false,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = "Motor Guard",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(bottom = 20.dp)
                .size(30.dp),
        )
    }
}
