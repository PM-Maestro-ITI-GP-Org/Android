package com.motorguard.ivi.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Mock connectivity state. Stands in for WifiManager / BluetoothAdapter until the app is
 * platform-signed (real radio control needs system privileges). App-scoped singletons so
 * the state survives navigating between Settings sub-tabs.
 */

data class WifiNetwork(val ssid: String, val secured: Boolean, val signal: Int) // signal 0..3

object WifiMock {
    var enabled by mutableStateOf(true)
    var connectedSsid by mutableStateOf<String?>("MotorGuard-5G")
    val known = mutableStateListOf("MotorGuard-5G")

    val networks = listOf(
        WifiNetwork("MotorGuard-5G", secured = true, signal = 3),
        WifiNetwork("Garage_WiFi", secured = true, signal = 2),
        WifiNetwork("ITI-Guest", secured = false, signal = 2),
        WifiNetwork("Neighbor_2.4", secured = true, signal = 1),
    )

    fun connect(ssid: String) {
        connectedSsid = ssid
        if (ssid !in known) known.add(ssid)
    }

    fun disconnect() {
        connectedSsid = null
    }

    fun forget(ssid: String) {
        known.remove(ssid)
        if (connectedSsid == ssid) connectedSsid = null
    }
}

enum class BtKind { PHONE, AUDIO, WEARABLE }

data class BtDevice(val name: String, val kind: BtKind)

object BtMock {
    var enabled by mutableStateOf(true)
    var connectedName by mutableStateOf<String?>("Abdelrahman’s iPhone")

    val paired = mutableStateListOf(
        BtDevice("Abdelrahman’s iPhone", BtKind.PHONE),
        BtDevice("Galaxy Buds Pro", BtKind.AUDIO),
        BtDevice("Pixel Watch", BtKind.WEARABLE),
    )

    fun toggleConnect(name: String) {
        connectedName = if (connectedName == name) null else name
    }

    fun unpair(name: String) {
        paired.removeAll { it.name == name }
        if (connectedName == name) connectedName = null
    }
}
