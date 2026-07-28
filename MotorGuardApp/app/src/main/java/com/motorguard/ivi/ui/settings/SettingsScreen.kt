package com.motorguard.ivi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.settings.panes.BluetoothPane
import com.motorguard.ivi.ui.settings.panes.SystemPane
import com.motorguard.ivi.ui.settings.panes.ThemePane
import com.motorguard.ivi.ui.settings.panes.WifiPane

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    WIFI("Wi-Fi", Icons.Filled.Wifi),
    BLUETOOTH("Bluetooth", Icons.Filled.Bluetooth),
    THEME("Theme & Display", Icons.Filled.Palette),
    SYSTEM("System", Icons.Filled.Tune),
}

@Composable
fun SettingsScreen() {
    var selected by rememberSaveable { mutableStateOf(SettingsTab.WIFI) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left ~30%: sub-tab list.
        Column(
            modifier = Modifier
                .weight(0.32f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Settings",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp),
            )
            SettingsTab.entries.forEach { tab ->
                SubTabRow(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { selected = tab },
                )
            }
        }

        // Right ~70%: detail pane.
        Box(
            modifier = Modifier
                .weight(0.68f)
                .fillMaxHeight()
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            when (selected) {
                SettingsTab.WIFI -> WifiPane()
                SettingsTab.BLUETOOTH -> BluetoothPane()
                SettingsTab.THEME -> ThemePane()
                SettingsTab.SYSTEM -> SystemPane()
            }
        }
    }
}

@Composable
private fun SubTabRow(
    tab: SettingsTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = tab.label,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
