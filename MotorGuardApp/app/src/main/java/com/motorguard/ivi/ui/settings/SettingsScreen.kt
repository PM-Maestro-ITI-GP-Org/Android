package com.motorguard.ivi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.motorguard.ivi.data.Conn
import com.motorguard.ivi.ui.settings.panes.BluetoothPane
import com.motorguard.ivi.ui.settings.panes.SystemPane
import com.motorguard.ivi.ui.settings.panes.ThemePane
import com.motorguard.ivi.ui.settings.panes.WifiPane
import com.motorguard.ivi.ui.theme.ThemeMode
import com.motorguard.ivi.ui.theme.ThemeState

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    WIFI("Wi-Fi", Icons.Filled.Wifi),
    BLUETOOTH("Bluetooth", Icons.Filled.Bluetooth),
    THEME("Theme & Display", Icons.Filled.Palette),
    SYSTEM("System", Icons.Filled.Tune),
}

@Composable
fun SettingsScreen() {
    var selected by rememberSaveable { mutableStateOf(SettingsTab.WIFI) }
    val systemDark = isSystemInDarkTheme()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Left ~32%: full-height sub-tab panel.
        Column(
            modifier = Modifier
                .weight(0.32f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Settings",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 14.dp),
            )
            SettingsTab.entries.forEach { tab ->
                SubTabRow(
                    tab = tab,
                    subtitle = subtitleFor(tab, systemDark),
                    selected = tab == selected,
                    onClick = { selected = tab },
                )
            }
        }

        // Right ~68%: detail panel.
        Box(
            modifier = Modifier
                .weight(0.68f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
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

private fun subtitleFor(tab: SettingsTab, systemDark: Boolean): String = when (tab) {
    SettingsTab.WIFI -> if (Conn.wifi.enabled) (Conn.wifi.connectedSsid ?: "On") else "Off"
    SettingsTab.BLUETOOTH -> if (Conn.bt.enabled) (Conn.bt.connectedName ?: "On") else "Off"
    SettingsTab.THEME -> {
        val mode = when (ThemeState.mode) {
            ThemeMode.DAY -> "Day"
            ThemeMode.NIGHT -> "Night"
            ThemeMode.AUTO -> if (systemDark) "Night" else "Day"
        }
        if (ThemeState.mode == ThemeMode.AUTO) "$mode · auto on" else mode
    }
    SettingsTab.SYSTEM -> "MotorGuard OS 1.0"
}

@Composable
private fun SubTabRow(
    tab: SettingsTab,
    subtitle: String,
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = tab.label,
                fontSize = 17.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
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
