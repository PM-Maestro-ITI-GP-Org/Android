package com.motorguard.ivi.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.Conn
import com.motorguard.ivi.data.ConnPolicy
import com.motorguard.ivi.ui.components.MgSwitch
import com.motorguard.ivi.ui.components.RowDivider
import com.motorguard.ivi.ui.components.SectionCard
import com.motorguard.ivi.ui.components.SettingRow

private data class ResetAction(val title: String, val message: String)

@Composable
fun SystemPane() {
    var updateStatus by remember { mutableStateOf("MotorGuard OS 1.0 (build 100)") }
    var pendingReset by remember { mutableStateOf<ResetAction?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        SectionCard(title = "Software") {
            SettingRow(
                title = "Check for updates",
                subtitle = updateStatus,
                leading = Icons.Filled.SystemUpdate,
                onClick = { updateStatus = "Up to date · checked just now" },
            )
        }

        // Which Wi-Fi/Bluetooth implementation is live. On the platform build this is already
        // true from the resource overlay; on a phone or the emulator it is the only way to reach
        // the real radios without rebuilding, which is exactly when you want to check them.
        SectionCard(title = "Connectivity source") {
            SettingRow(
                title = "Use real Wi-Fi & Bluetooth",
                subtitle = if (Conn.useReal) {
                    "Live radios · control needs a platform-signed build"
                } else {
                    "Demo data"
                },
                leading = Icons.Filled.SettingsEthernet,
                onClick = { Conn.switchSource(!Conn.useReal) },
                trailing = {
                    MgSwitch(
                        checked = Conn.useReal,
                        onCheckedChange = { Conn.switchSource(it) },
                    )
                },
            )
            RowDivider()
            SettingRow(
                title = "Direct radio control",
                subtitle = if (ConnPolicy.directOnly) {
                    "Toggles act here · no system screens"
                } else {
                    "Fall back to system settings when blocked"
                },
                leading = Icons.Filled.Bolt,
                onClick = { ConnPolicy.directOnly = !ConnPolicy.directOnly },
                trailing = {
                    MgSwitch(
                        checked = ConnPolicy.directOnly,
                        onCheckedChange = { ConnPolicy.directOnly = it },
                    )
                },
            )
        }

        SectionCard(title = "About vehicle") {
            InfoRow("Model", "Motor Guard EV")
            RowDivider()
            InfoRow("VIN", "MG1EV0AB1CD234567")
            RowDivider()
            InfoRow("Software", "MotorGuard OS 1.0")
            RowDivider()
            InfoRow("Android", "Automotive 15 (API 35)")
        }

        SectionCard(title = "Reset") {
            SettingRow(
                title = "Reset network settings",
                leading = Icons.Filled.RestartAlt,
                onClick = {
                    pendingReset = ResetAction(
                        "Reset network settings?",
                        "Wi-Fi and Bluetooth connections will be removed.",
                    )
                },
            )
            RowDivider()
            SettingRow(
                title = "Reset app preferences",
                leading = Icons.Filled.RestartAlt,
                onClick = {
                    pendingReset = ResetAction(
                        "Reset app preferences?",
                        "Disabled apps and default-app choices will be reset.",
                    )
                },
            )
            RowDivider()
            SettingRow(
                title = "Factory reset",
                subtitle = "Erase all data",
                leading = Icons.Filled.RestartAlt,
                onClick = {
                    pendingReset = ResetAction(
                        "Factory reset?",
                        "This erases all data and returns the system to factory settings. This cannot be undone.",
                    )
                },
            )
        }
    }

    // Destructive actions always confirm (mock — no real reset performed).
    pendingReset?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            icon = { androidx.compose.material3.Icon(Icons.Filled.Info, contentDescription = null) },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = { pendingReset = null }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingReset = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    SettingRow(title = label, trailing = {
        Text(value, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    })
}
