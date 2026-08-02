@file:Suppress("DEPRECATION")

package com.motorguard.ivi.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Real Wi-Fi via WifiManager. Requires the app to be platform-signed / privileged for
 * setEnabled + connect/forget to succeed (see privapp-permissions in the AOSP build).
 * On an unprivileged build those calls no-op; reads still work where permitted.
 *
 * NOTE: validate on real hardware (RPi). Emulator Wi-Fi is virtual and limited.
 */
@SuppressLint("MissingPermission")
class RealWifiRepo(private val context: Context) : WifiRepo {

    private val wm = context.getSystemService(WifiManager::class.java)

    private var _enabled by mutableStateOf(wm?.isWifiEnabled == true)
    override val enabled: Boolean get() = _enabled
    override var connectedSsid by mutableStateOf<String?>(currentSsid())
        private set

    private val _known = mutableStateListOf<String>()
    override val known: List<String> get() = _known

    private val _networks = mutableStateListOf<WifiNetwork>()
    override val networks: List<WifiNetwork> get() = _networks

    init {
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = refresh()
        }, filter)
        refresh()
        runCatching { wm?.startScan() }
    }

    private fun currentSsid(): String? {
        val raw = wm?.connectionInfo?.ssid ?: return null
        if (raw == "<unknown ssid>") return null
        return raw.trim('"').ifBlank { null }
    }

    private fun refresh() {
        _enabled = wm?.isWifiEnabled == true
        connectedSsid = currentSsid()
        _networks.clear()
        wm?.scanResults?.forEach { r ->
            if (r.SSID.isNullOrBlank()) return@forEach
            val secured = listOf("WPA", "WEP", "PSK", "EAP").any { r.capabilities.contains(it) }
            val level = WifiManager.calculateSignalLevel(r.level, 4).coerceIn(0, 3)
            _networks.add(WifiNetwork(r.SSID, secured, level))
        }
        // Scan results are gated behind location services being on, but the network we are
        // actually joined to is not something the driver should have to take on faith. If the
        // scan did not list it, add it so the pane always shows the connection it is reporting.
        connectedSsid?.let { ssid ->
            if (_networks.none { it.ssid == ssid }) {
                _networks.add(0, WifiNetwork(ssid, secured = true, signal = 3))
            }
        }
        _known.clear()
        runCatching {
            wm?.configuredNetworks?.forEach { it.SSID?.trim('"')?.let(_known::add) }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        runCatching { wm?.isWifiEnabled = enabled }
    }

    // TODO(on-device): modern connect flow. addNetwork/enableNetwork works for system apps.
    override fun connect(ssid: String, password: String?) {
        runCatching {
            val cfg = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (password != null) preSharedKey = "\"$password\""
                else allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            }
            val id = wm?.addNetwork(cfg) ?: -1
            if (id != -1) {
                wm?.enableNetwork(id, true)
                wm?.reconnect()
            }
        }
    }

    override fun disconnect() { runCatching { wm?.disconnect() } }

    override fun forget(ssid: String) {
        runCatching {
            wm?.configuredNetworks?.firstOrNull { it.SSID?.trim('"') == ssid }?.let {
                wm?.removeNetwork(it.networkId)
                wm?.saveConfiguration()
            }
        }
        if (connectedSsid == ssid) connectedSsid = null
    }
}
