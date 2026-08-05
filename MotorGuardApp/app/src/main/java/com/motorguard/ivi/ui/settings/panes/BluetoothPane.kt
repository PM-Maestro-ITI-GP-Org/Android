package com.motorguard.ivi.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.BtActivity
import com.motorguard.ivi.data.BtDevice
import com.motorguard.ivi.data.BtKind
import com.motorguard.ivi.data.Conn
import com.motorguard.ivi.ui.components.MgSwitch
import com.motorguard.ivi.ui.components.RowDivider
import com.motorguard.ivi.ui.components.SectionCard
import com.motorguard.ivi.ui.components.SettingRow
import com.motorguard.ivi.ui.components.StatusLine

/**
 * Bluetooth settings, written around the fact that the car is the **sink**.
 *
 * The pane leads with "Discoverable" rather than with a device list because that is the flow
 * that actually happens in a car: the driver makes the head unit visible and pairs from the
 * phone, where the passkey dialog and the keyboard already are. Scanning outward is kept for
 * speakers and for re-finding a phone that is already bonded, but it is the second option, not
 * the first.
 */
@Composable
fun BluetoothPane() {
    val bt = Conn.bt
    var menuFor by remember { mutableStateOf<BtDevice?>(null) }
    var renameFor by remember { mutableStateOf<BtDevice?>(null) }

    // Discovery is expensive and saturates the radio, so it is tied to the pane being on screen
    // — "list refreshes while the pane is open" from docs/06-settings.md — and always stopped on
    // the way out, including when the driver switches sub-tabs mid-scan.
    DisposableEffect(bt, bt.enabled) {
        if (bt.enabled) bt.startScan()
        onDispose { bt.stopScan() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        SectionCard {
            SettingRow(
                title = "Bluetooth",
                subtitle = if (bt.enabled) "On" else "Off",
                leading = Icons.Filled.Bluetooth,
                onClick = { bt.setEnabled(!bt.enabled) },
                trailing = {
                    MgSwitch(
                        checked = bt.enabled,
                        onCheckedChange = { bt.setEnabled(it) },
                    )
                },
            )
        }

        if (bt.enabled) {
            // What the radio is doing. Sits directly under the toggle so it is the first thing
            // read after switching Bluetooth on.
            StatusLine(
                text = bt.activity.describe(),
                busy = bt.activity is BtActivity.Scanning ||
                    bt.activity is BtActivity.Pairing ||
                    bt.activity is BtActivity.Connecting,
                error = bt.activity is BtActivity.Failed,
                actionLabel = if (bt.activity is BtActivity.Scanning) "Stop" else "Scan",
                onAction = { if (bt.activity is BtActivity.Scanning) bt.stopScan() else bt.startScan() },
            )

            SectionCard(title = "Connect a phone") {
                SettingRow(
                    title = "Discoverable",
                    subtitle = if (bt.discoverable) {
                        "Visible as “${bt.localName}” — pair from your phone now"
                    } else {
                        "Turn on, then pick “${bt.localName}” on your phone"
                    },
                    leading = Icons.Filled.Visibility,
                    onClick = { bt.requestDiscoverable(!bt.discoverable) },
                    trailing = {
                        MgSwitch(
                            checked = bt.discoverable,
                            onCheckedChange = { bt.requestDiscoverable(it) },
                        )
                    },
                )
            }

            if (bt.paired.isNotEmpty()) {
                SectionCard(title = "Paired devices") {
                    bt.paired.forEachIndexed { i, device ->
                        val connected = bt.connectedName == device.name
                        val connecting = (bt.activity as? BtActivity.Connecting)?.name == device.name
                        SettingRow(
                            title = device.name,
                            subtitle = when {
                                connecting -> "Connecting…"
                                connected -> "Connected"
                                else -> "Paired"
                            },
                            leading = device.kind.icon(),
                            onClick = { bt.toggleConnect(device.address) },
                            onLongClick = { menuFor = device },
                            trailing = {
                                Text(
                                    text = when {
                                        connecting -> "Connecting…"
                                        connected -> "Connected"
                                        else -> "Tap to connect"
                                    },
                                    color = if (connected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    },
                                )
                            },
                        )
                        if (i < bt.paired.lastIndex) RowDivider()
                    }
                }
            }

            SectionCard(title = "Available devices") {
                if (bt.discovered.isEmpty()) {
                    SettingRow(
                        title = if (bt.activity is BtActivity.Scanning) {
                            "Searching for devices…"
                        } else {
                            "No devices found"
                        },
                        subtitle = if (bt.activity is BtActivity.Scanning) {
                            null
                        } else {
                            "Tap Scan to search again"
                        },
                        leading = Icons.AutoMirrored.Filled.BluetoothSearching,
                        enabled = false,
                    )
                } else {
                    bt.discovered.forEachIndexed { i, device ->
                        val pairing = (bt.activity as? BtActivity.Pairing)?.name == device.name
                        SettingRow(
                            title = device.name,
                            subtitle = if (pairing) "Pairing…" else device.address,
                            leading = device.kind.icon(),
                            onClick = { bt.pair(device.address) },
                            trailing = {
                                Text(
                                    text = if (pairing) "Pairing…" else "Tap to pair",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            },
                        )
                        if (i < bt.discovered.lastIndex) RowDivider()
                    }
                }
            }
        }
    }

    // Long-press: connect/disconnect · rename · unpair.
    menuFor?.let { device ->
        val connected = bt.connectedName == device.name
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(device.name) },
            text = { Text(if (connected) "Connected" else "Paired") },
            confirmButton = {
                Column {
                    TextButton(onClick = { bt.toggleConnect(device.address); menuFor = null }) {
                        Text(if (connected) "Disconnect" else "Connect")
                    }
                    TextButton(onClick = { renameFor = device; menuFor = null }) { Text("Rename") }
                    TextButton(onClick = { bt.unpair(device.address); menuFor = null }) {
                        Text("Unpair", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { menuFor = null }) { Text("Cancel") }
            },
        )
    }

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
                        bt.rename(device.address, name)
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

/** The status line's sentence. Kept next to the pane so the wording stays with the layout. */
private fun BtActivity.describe(): String = when (this) {
    is BtActivity.Idle -> "Ready"
    is BtActivity.Scanning -> "Scanning for nearby devices…"
    is BtActivity.Pairing -> "Pairing with $name — confirm on the device"
    is BtActivity.Connecting -> "Connecting to $name…"
    is BtActivity.Failed -> if (name.isBlank()) reason else "$name: $reason"
}

private fun BtKind.icon(): ImageVector = when (this) {
    BtKind.PHONE -> Icons.Filled.Smartphone
    BtKind.AUDIO -> Icons.Filled.Headphones
    BtKind.WEARABLE -> Icons.Filled.Watch
}
