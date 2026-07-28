package com.motorguard.ivi.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.ui.components.MgSwitch
import com.motorguard.ivi.ui.components.RowDivider
import com.motorguard.ivi.ui.components.SectionCard
import com.motorguard.ivi.ui.components.SettingRow
import com.motorguard.ivi.ui.settings.BtDevice
import com.motorguard.ivi.ui.settings.BtKind
import com.motorguard.ivi.ui.settings.BtMock

@Composable
fun BluetoothPane() {
    var menuFor by remember { mutableStateOf<BtDevice?>(null) }
    var renameFor by remember { mutableStateOf<BtDevice?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        SectionCard {
            SettingRow(
                title = "Bluetooth",
                subtitle = if (BtMock.enabled) "On" else "Off",
                leading = Icons.Filled.Bluetooth,
                onClick = { BtMock.enabled = !BtMock.enabled },
                trailing = {
                    MgSwitch(
                        checked = BtMock.enabled,
                        onCheckedChange = { BtMock.enabled = it },
                    )
                },
            )
        }

        if (BtMock.enabled) {
            SectionCard(title = "Paired devices") {
                BtMock.paired.forEachIndexed { i, device ->
                    val connected = BtMock.connectedName == device.name
                    SettingRow(
                        title = device.name,
                        subtitle = when {
                            connected -> "Connected"
                            else -> "Paired"
                        },
                        leading = device.kind.icon(),
                        onClick = { BtMock.toggleConnect(device.name) },
                        onLongClick = { menuFor = device },
                        trailing = {
                            Text(
                                text = if (connected) "Connected" else "Tap to connect",
                                color = if (connected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                },
                            )
                        },
                    )
                    if (i < BtMock.paired.lastIndex) RowDivider()
                }
            }
        }
    }

    // Long-press: connect/disconnect · rename · unpair.
    menuFor?.let { device ->
        val connected = BtMock.connectedName == device.name
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(device.name) },
            text = { Text(if (connected) "Connected" else "Paired") },
            confirmButton = {
                Column {
                    TextButton(onClick = { BtMock.toggleConnect(device.name); menuFor = null }) {
                        Text(if (connected) "Disconnect" else "Connect")
                    }
                    TextButton(onClick = { renameFor = device; menuFor = null }) { Text("Rename") }
                    TextButton(onClick = { BtMock.unpair(device.name); menuFor = null }) {
                        Text("Unpair", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { menuFor = null }) { Text("Cancel") }
            },
        )
    }

    // Rename dialog (mock).
    renameFor?.let { device ->
        var name by remember { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val idx = BtMock.paired.indexOfFirst { it.name == device.name }
                        if (idx >= 0) {
                            if (BtMock.connectedName == device.name) BtMock.connectedName = name
                            BtMock.paired[idx] = device.copy(name = name)
                        }
                        renameFor = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameFor = null }) { Text("Cancel") }
            },
        )
    }
}

private fun BtKind.icon(): ImageVector = when (this) {
    BtKind.PHONE -> Icons.Filled.Smartphone
    BtKind.AUDIO -> Icons.Filled.Headphones
    BtKind.WEARABLE -> Icons.Filled.Watch
}
