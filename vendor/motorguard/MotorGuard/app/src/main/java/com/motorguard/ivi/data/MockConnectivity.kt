package com.motorguard.ivi.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Emulator / demo Wi-Fi. Realistic fake data with all the interactions wired. */
class MockWifiRepo : WifiRepo {
    private var _enabled by mutableStateOf(true)
    override val enabled: Boolean get() = _enabled
    override var connectedSsid by mutableStateOf<String?>("MotorGuard-5G")
        private set

    // Saved networks persist: "known" is a claim about the past, so forgetting it on every
    // restart would make Forget the only button that appears to work.
    private val _known = mutableStateListOf<String>().apply {
        val saved = LocalStore.getString(LocalStore.Keys.WIFI_KNOWN)
        if (saved.isNullOrBlank()) add("MotorGuard-5G") else addAll(saved.split('\n').filter { it.isNotBlank() })
    }
    override val known: List<String> get() = _known

    private fun persistKnown() =
        LocalStore.putString(LocalStore.Keys.WIFI_KNOWN, _known.joinToString("\n"))

    override val networks = listOf(
        WifiNetwork("MotorGuard-5G", secured = true, signal = 3),
        WifiNetwork("Garage_WiFi", secured = true, signal = 2),
        WifiNetwork("ITI-Guest", secured = false, signal = 2),
        WifiNetwork("Neighbor_2.4", secured = true, signal = 1),
    )

    override fun setEnabled(enabled: Boolean) { _enabled = enabled }

    override fun connect(ssid: String, password: String?) {
        connectedSsid = ssid
        if (ssid !in _known) {
            _known.add(ssid)
            persistKnown()
        }
    }

    override fun disconnect() { connectedSsid = null }

    override fun forget(ssid: String) {
        _known.remove(ssid)
        persistKnown()
        if (connectedSsid == ssid) connectedSsid = null
    }
}

/** Emulator / demo Bluetooth. */
class MockBtRepo : BtRepo {
    private var _enabled by mutableStateOf(true)
    override val enabled: Boolean get() = _enabled
    override var connectedName by mutableStateOf<String?>("Abdelrahman’s iPhone")
        private set

    private val _paired = mutableStateListOf(
        BtDevice("Abdelrahman’s iPhone", BtKind.PHONE),
        BtDevice("Galaxy Buds Pro", BtKind.AUDIO),
        BtDevice("Pixel Watch", BtKind.WEARABLE),
    )
    override val paired: List<BtDevice> get() = _paired

    override fun setEnabled(enabled: Boolean) { _enabled = enabled }

    override fun toggleConnect(name: String) {
        connectedName = if (connectedName == name) null else name
    }

    override fun unpair(name: String) {
        _paired.removeAll { it.name == name }
        if (connectedName == name) connectedName = null
    }

    override fun rename(oldName: String, newName: String) {
        val i = _paired.indexOfFirst { it.name == oldName }
        if (i >= 0) {
            if (connectedName == oldName) connectedName = newName
            _paired[i] = _paired[i].copy(name = newName)
        }
    }
}
