@file:Suppress("DEPRECATION")

package com.motorguard.ivi.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
        // One scan result per *BSSID*, not per network: a router advertising 2.4 and 5 GHz, or a
        // site with several access points, returns the same SSID many times. Collapse them to the
        // strongest sighting — the driver is choosing a network, not a radio — and order the list
        // by signal so the usable ones are at the top.
        wm?.scanResults
            ?.filter { !it.SSID.isNullOrBlank() }
            ?.groupBy { it.SSID }
            ?.map { (ssid, sightings) ->
                val best = sightings.maxBy { it.level }
                WifiNetwork(
                    ssid = ssid,
                    secured = listOf("WPA", "WEP", "PSK", "EAP").any { best.capabilities.contains(it) },
                    signal = WifiManager.calculateSignalLevel(best.level, 4).coerceIn(0, 3),
                )
            }
            ?.sortedByDescending { it.signal }
            ?.let(_networks::addAll)
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

    /**
     * `setWifiEnabled` has been a no-op for non-system apps since Android 10 — it returns false
     * and the radio stays where it was. Rather than leave a switch that lies, fall back to the
     * system panel so the driver can flip it there.
     */
    override fun setEnabled(enabled: Boolean) {
        val applied = runCatching {
            wm?.setWifiEnabled(enabled) == true
        }.getOrDefault(false)
        if (!applied) openSystemWifiPicker()
        refresh()
    }

    /**
     * Join a network.
     *
     * `addNetwork`/`enableNetwork` still work for a system app, which is the platform build this
     * is written for. For everyone else Android 10 closed that door: the call returns -1 and the
     * radio does not move. Tapping a network and having nothing happen is the worst of both
     * worlds, so when the direct path fails we hand off to the system Wi-Fi picker, where the
     * driver can complete the join. See [openSystemWifiPicker].
     */
    override fun connect(ssid: String, password: String?) {
        val joined = runCatching {
            val cfg = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (password != null) preSharedKey = "\"$password\""
                else allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            }
            val id = wm?.addNetwork(cfg) ?: -1
            if (id == -1) return@runCatching false
            wm?.enableNetwork(id, true)
            wm?.reconnect()
            true
        }.getOrDefault(false)

        if (!joined) openSystemWifiPicker()
        refresh()
    }

    /**
     * The system's own Wi-Fi chooser. Settings.Panel is an inline sheet on API 29+, which keeps
     * the driver in context; the full settings screen is the fallback for anything older.
     */
    private fun openSystemWifiPicker() {
        val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.provider.Settings.Panel.ACTION_WIFI
        } else {
            android.provider.Settings.ACTION_WIFI_SETTINGS
        }
        runCatching {
            context.applicationContext.startActivity(
                Intent(panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
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
