package com.motorguard.ivi.data

import android.os.Handler
import android.os.Looper
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

    // The mock has no radio to reject a password, so a join here never fails.
    override val lastError: String? = null
    override fun clearError() = Unit

    override var scanning by mutableStateOf(false)
        private set
    override var connectingSsid by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())

    override fun setEnabled(enabled: Boolean) { _enabled = enabled }

    // Deliberately not instant: the status line exists to show a scan in flight, and a mock that
    // finishes before the first frame would never exercise it.
    override fun startScan() {
        if (!_enabled || scanning) return
        scanning = true
        main.postDelayed({ scanning = false }, 2_000L)
    }

    override fun connect(ssid: String, password: String?) {
        connectingSsid = ssid
        main.postDelayed({ connectingSsid = null }, 1_200L)
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

/**
 * Emulator / demo Bluetooth.
 *
 * The scan is faked on a timer rather than resolving instantly: an instant result would hide
 * exactly the state the pane was changed to show, and the demo would not exercise the spinner
 * or the "Scanning…" line at all.
 */
class MockBtRepo : BtRepo {
    private var _enabled by mutableStateOf(true)
    override val enabled: Boolean get() = _enabled
    override var connectedName by mutableStateOf<String?>("Abdelrahman’s iPhone")
        private set

    override var activity by mutableStateOf<BtActivity>(BtActivity.Idle)
        private set
    override var discoverable by mutableStateOf(false)
        private set
    override val localName = "Motor Guard"

    private val _paired = mutableStateListOf(
        BtDevice("AC:DE:48:00:11:22", "Abdelrahman’s iPhone", BtKind.PHONE),
        BtDevice("AC:DE:48:00:11:23", "Galaxy Buds Pro", BtKind.AUDIO),
        BtDevice("AC:DE:48:00:11:24", "Pixel Watch", BtKind.WEARABLE),
    )
    override val paired: List<BtDevice> get() = _paired

    private val _discovered = mutableStateListOf<BtDevice>()
    override val discovered: List<BtDevice> get() = _discovered

    private val main = Handler(Looper.getMainLooper())

    override fun setEnabled(enabled: Boolean) { _enabled = enabled }

    override fun startScan() {
        if (!_enabled) return
        _discovered.clear()
        activity = BtActivity.Scanning
        main.postDelayed({
            _discovered.addAll(
                listOf(
                    BtDevice("AC:DE:48:00:22:01", "Yousself’s Pixel", BtKind.PHONE, bonded = false),
                    BtDevice("AC:DE:48:00:22:02", "JBL Flip 6", BtKind.AUDIO, bonded = false),
                ),
            )
            activity = BtActivity.Idle
        }, 2_500L)
    }

    override fun stopScan() {
        main.removeCallbacksAndMessages(null)
        if (activity is BtActivity.Scanning) activity = BtActivity.Idle
    }

    override fun requestDiscoverable(on: Boolean) {
        discoverable = on
        if (on) main.postDelayed({ discoverable = false }, 300_000L)
    }

    override fun pair(address: String) {
        val found = _discovered.firstOrNull { it.address == address } ?: return
        activity = BtActivity.Pairing(found.name)
        main.postDelayed({
            _discovered.remove(found)
            _paired.add(found.copy(bonded = true))
            activity = BtActivity.Idle
        }, 1_800L)
    }

    override fun toggleConnect(address: String) {
        val device = _paired.firstOrNull { it.address == address } ?: return
        if (connectedName == device.name) {
            connectedName = null
            return
        }
        activity = BtActivity.Connecting(device.name)
        main.postDelayed({
            connectedName = device.name
            activity = BtActivity.Idle
        }, 1_200L)
    }

    override fun unpair(address: String) {
        val device = _paired.firstOrNull { it.address == address } ?: return
        _paired.remove(device)
        if (connectedName == device.name) connectedName = null
    }

    override fun rename(address: String, newName: String) {
        val i = _paired.indexOfFirst { it.address == address }
        if (i >= 0) {
            if (connectedName == _paired[i].name) connectedName = newName
            _paired[i] = _paired[i].copy(name = newName)
        }
    }
}
