package com.motorguard.ivi.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.Conn
import com.motorguard.ivi.data.WifiNetwork
import com.motorguard.ivi.ui.components.MgSwitch
import com.motorguard.ivi.ui.components.RowDivider
import com.motorguard.ivi.ui.components.SectionCard
import com.motorguard.ivi.ui.components.SettingRow
import com.motorguard.ivi.ui.components.StatusLine

@Composable
fun WifiPane() {
    val wifi = Conn.wifi
    var passwordFor by remember { mutableStateOf<WifiNetwork?>(null) }
    var menuFor by remember { mutableStateOf<WifiNetwork?>(null) }

    // A pane opened onto a stale list is the other half of the "is it scanning?" problem, so
    // arriving here kicks off a fresh scan the status line can then report on.
    DisposableEffect(wifi, wifi.enabled) {
        if (wifi.enabled) wifi.startScan()
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        SectionCard {
            SettingRow(
                title = "Wi-Fi",
                subtitle = if (wifi.enabled) "On" else "Off",
                leading = Icons.Filled.Wifi,
                onClick = { wifi.setEnabled(!wifi.enabled) },
                trailing = {
                    MgSwitch(
                        checked = wifi.enabled,
                        onCheckedChange = { wifi.setEnabled(it) },
                    )
                },
            )
        }

        // A refused password is otherwise silent: the row simply never says "Connected" and the
        // driver is left guessing whether they mistyped or the network is out of range.
        wifi.lastError?.let { error ->
            SectionCard {
                SettingRow(
                    title = error,
                    subtitle = "Tap to dismiss, then try again",
                    leading = Icons.Filled.Warning,
                    onClick = { wifi.clearError() },
                )
            }
        }

        if (wifi.enabled) {
            // Same role as the Bluetooth pane's line: says whether the radio is busy, so an empty
            // list can be read as "found nothing" rather than "broken".
            StatusLine(
                text = when {
                    wifi.connectingSsid != null -> "Connecting to ${wifi.connectingSsid}…"
                    wifi.scanning -> "Scanning for networks…"
                    wifi.networks.isEmpty() -> "No networks found"
                    else -> "${wifi.networks.size} networks nearby"
                },
                busy = wifi.scanning || wifi.connectingSsid != null,
                actionLabel = if (wifi.scanning) null else "Scan",
                onAction = { wifi.startScan() },
            )

            SectionCard(title = "Networks") {
                // Android returns no scan results at all while location services are off, whatever
                // permissions the app holds. Saying so beats an empty card that reads as a bug.
                if (wifi.networks.isEmpty()) {
                    SettingRow(
                        title = "No networks found",
                        subtitle = "Nearby Wi-Fi needs location services switched on",
                        leading = Icons.Filled.Wifi,
                    )
                }
                wifi.networks.forEachIndexed { i, net ->
                    val connected = wifi.connectedSsid == net.ssid
                    val known = net.ssid in wifi.known
                    SettingRow(
                        title = net.ssid,
                        subtitle = when {
                            connected -> "Connected"
                            known -> "Saved"
                            net.secured -> "Secured"
                            else -> "Open"
                        },
                        leading = Icons.Filled.Wifi,
                        onClick = {
                            when {
                                connected -> {}
                                net.secured && !known -> passwordFor = net
                                else -> wifi.connect(net.ssid)
                            }
                        },
                        onLongClick = { menuFor = net },
                        trailing = {
                            if (connected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Connected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            } else if (net.secured) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = "Secured",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                    if (i < wifi.networks.lastIndex) RowDivider()
                }
            }
        }
    }

    // Password entry (would be blocked while moving per CarUxRestrictions — TODO).
    passwordFor?.let { net ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passwordFor = null },
            title = { Text("Connect to ${net.ssid}") },
            text = {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pw.isNotEmpty(),
                    onClick = {
                        wifi.connect(net.ssid, pw)
                        passwordFor = null
                    },
                ) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { passwordFor = null }) { Text("Cancel") }
            },
        )
    }

    // Long-press menu: connect/disconnect/forget.
    menuFor?.let { net ->
        val connected = wifi.connectedSsid == net.ssid
        val known = net.ssid in wifi.known
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(net.ssid) },
            text = { Text(if (connected) "Connected" else if (known) "Saved network" else "Available network") },
            confirmButton = {
                Column {
                    if (connected) {
                        TextButton(onClick = { wifi.disconnect(); menuFor = null }) { Text("Disconnect") }
                    } else {
                        TextButton(onClick = {
                            if (net.secured && !known) passwordFor = net else wifi.connect(net.ssid)
                            menuFor = null
                        }) { Text("Connect") }
                    }
                    if (known) {
                        TextButton(onClick = { wifi.forget(net.ssid); menuFor = null }) {
                            Text("Forget", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { menuFor = null }) { Text("Cancel") }
            },
        )
    }
}
