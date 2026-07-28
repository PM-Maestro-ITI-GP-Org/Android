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
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.ui.components.MgSwitch
import com.motorguard.ivi.ui.components.RowDivider
import com.motorguard.ivi.ui.components.SectionCard
import com.motorguard.ivi.ui.components.SettingRow
import com.motorguard.ivi.ui.settings.WifiMock
import com.motorguard.ivi.ui.settings.WifiNetwork

@Composable
fun WifiPane() {
    var passwordFor by remember { mutableStateOf<WifiNetwork?>(null) }
    var menuFor by remember { mutableStateOf<WifiNetwork?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        SectionCard {
            SettingRow(
                title = "Wi-Fi",
                subtitle = if (WifiMock.enabled) "On" else "Off",
                leading = Icons.Filled.Wifi,
                onClick = { WifiMock.enabled = !WifiMock.enabled },
                trailing = {
                    MgSwitch(
                        checked = WifiMock.enabled,
                        onCheckedChange = { WifiMock.enabled = it },
                    )
                },
            )
        }

        if (WifiMock.enabled) {
            SectionCard(title = "Networks") {
                WifiMock.networks.forEachIndexed { i, net ->
                    val connected = WifiMock.connectedSsid == net.ssid
                    val known = net.ssid in WifiMock.known
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
                                else -> WifiMock.connect(net.ssid)
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
                    if (i < WifiMock.networks.lastIndex) RowDivider()
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
                        WifiMock.connect(net.ssid)
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
        val connected = WifiMock.connectedSsid == net.ssid
        val known = net.ssid in WifiMock.known
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(net.ssid) },
            text = { Text(if (connected) "Connected" else if (known) "Saved network" else "Available network") },
            confirmButton = {
                Column {
                    if (connected) {
                        TextButton(onClick = { WifiMock.disconnect(); menuFor = null }) { Text("Disconnect") }
                    } else {
                        TextButton(onClick = {
                            if (net.secured && !known) passwordFor = net else WifiMock.connect(net.ssid)
                            menuFor = null
                        }) { Text("Connect") }
                    }
                    if (known) {
                        TextButton(onClick = { WifiMock.forget(net.ssid); menuFor = null }) {
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
