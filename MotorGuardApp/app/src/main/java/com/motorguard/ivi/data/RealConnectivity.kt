@file:Suppress("DEPRECATION")

package com.motorguard.ivi.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Real Wi-Fi via WifiManager. Requires the app to be platform-signed / privileged for
 * setEnabled + connect/forget to succeed (see privapp-permissions in the AOSP build).
 * On an unprivileged build those calls no-op; reads still work where permitted.
 */
@SuppressLint("MissingPermission")
class RealWifiRepo(private val context: Context) : WifiRepo {

    private val wm = context.getSystemService(WifiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var _enabled by mutableStateOf(wm?.isWifiEnabled == true)
    override val enabled: Boolean get() = _enabled
    override var connectedSsid by mutableStateOf<String?>(currentSsid())
        private set

    /**
     * Whole-list state rather than a [androidx.compose.runtime.mutableStateListOf].
     *
     * A SnapshotStateList notifies on every mutation, so the old `clear()` + `addAll()` was two
     * observable writes with an empty list in between — and on a busy radio, where a scan result
     * or an ACL broadcast lands every few hundred ms, Compose readily recomposed against that
     * empty middle. That is what the list flickering on the Pi actually was. One assignment of a
     * finished list cannot be observed half-applied.
     */
    private var _known by mutableStateOf<List<String>>(emptyList())
    override val known: List<String> get() = _known

    private var _networks by mutableStateOf<List<WifiNetwork>>(emptyList())
    override val networks: List<WifiNetwork> get() = _networks

    override var lastError by mutableStateOf<String?>(null)
        private set

    override var scanning by mutableStateOf(false)
        private set

    /** Mirrors [pending], exposed for the pane's status line. */
    override val connectingSsid: String? get() = pending?.ssid

    override fun clearError() { lastError = null }

    /**
     * The join we started and have not yet seen succeed.
     *
     * [WifiManager.addNetwork] persists the configuration the instant it is called — before a
     * single packet is exchanged — so a typo'd password leaves a saved network behind that the
     * platform then quietly disables. Holding the id here is what lets [dropPendingJoin] take it
     * back out again. Only ever a network *this* session added, so a long-standing network that
     * hiccups once is never removed underneath the driver.
     */
    private data class PendingJoin(val ssid: String, val networkId: Int)

    private var pending: PendingJoin? = null

    init {
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            // Carries EXTRA_SUPPLICANT_ERROR, the only immediate signal that the passphrase was
            // rejected. Everything else just looks like "not connected yet".
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> onSupplicantState(intent)
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                        scanning = false
                        handler.removeCallbacksAndMessages(SCAN_TOKEN)
                    }
                }
                refresh()
            }
        }, filter)
        refresh()
        startScan()
    }

    /**
     * Ask the platform for a fresh scan.
     *
     * `startScan` is throttled — four calls per two minutes for a foreground app, and it returns
     * false once the budget is spent — so a refused scan clears the flag immediately rather than
     * leaving a spinner running against a scan that was never started. The timeout covers the
     * other case: a scan that is accepted and then never delivers SCAN_RESULTS_AVAILABLE.
     */
    override fun startScan() {
        if (wm?.isWifiEnabled != true) return
        val started = runCatching { wm.startScan() }.getOrDefault(false)
        scanning = started
        if (!started) return
        handler.removeCallbacksAndMessages(SCAN_TOKEN)
        handler.postAtTime({ scanning = false }, SCAN_TOKEN, android.os.SystemClock.uptimeMillis() + SCAN_TIMEOUT_MS)
    }

    private fun currentSsid(): String? {
        val raw = wm?.connectionInfo?.ssid ?: return null
        if (raw == "<unknown ssid>") return null
        return raw.trim('"').ifBlank { null }
    }

    // ------------------------------------------------------------------ join outcome

    private fun onSupplicantState(intent: Intent) {
        val error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
        if (error == WifiManager.ERROR_AUTHENTICATING) {
            failPendingJoin("Wrong password")
            return
        }
        // A completed association is the only thing that makes a configuration worth keeping.
        val state = intent.getParcelableExtra<SupplicantState>(WifiManager.EXTRA_NEW_STATE)
        if (state == SupplicantState.COMPLETED && currentSsid() == pending?.ssid) {
            pending = null
        }
    }

    /**
     * Whether the platform has disabled a configuration because its credentials were refused.
     *
     * Mirrors `StandardWifiEntry.isDisabledByWrongPassword` in AOSP's WifiTrackerLib: a network
     * counts as bad-credential only if it is not currently selectable *and* has never once
     * connected — otherwise a single failed re-auth on a known-good network would look identical.
     * `NetworkSelectionStatus` is @hide, so it is read reflectively; on a build where that fails
     * the supplicant broadcast above is still the primary signal.
     */
    private fun isDisabledByBadCredentials(config: WifiConfiguration): Boolean = runCatching {
        val status = WifiConfiguration::class.java
            .getMethod("getNetworkSelectionStatus")
            .invoke(config) ?: return@runCatching false
        val cls = status.javaClass

        val enabled = cls.getMethod("getNetworkSelectionStatus").invoke(status) as Int == 0
        val everConnected = runCatching {
            cls.getMethod("hasEverConnected").invoke(status) as Boolean
        }.getOrDefault(false)
        if (enabled && everConnected) return@runCatching false

        val counterOf = cls.getMethod("getDisableReasonCounter", Int::class.javaPrimitiveType)
        AUTH_DISABLE_REASONS.any { name ->
            runCatching {
                val reason = cls.getField(name).getInt(null)
                (counterOf.invoke(status, reason) as Int) > 0
            }.getOrDefault(false)
        }
    }.getOrDefault(false)

    private fun failPendingJoin(reason: String) {
        val join = pending ?: return
        pending = null
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        runCatching {
            wm?.removeNetwork(join.networkId)
            wm?.saveConfiguration()
        }
        lastError = "$reason — ${join.ssid} was not saved"
        refresh()
    }

    private fun refresh() {
        _enabled = wm?.isWifiEnabled == true
        connectedSsid = currentSsid()

        // One scan result per *BSSID*, not per network: a router advertising 2.4 and 5 GHz, or a
        // site with several access points, returns the same SSID many times. Collapse them to the
        // strongest sighting — the driver is choosing a network, not a radio — and order the list
        // by signal so the usable ones are at the top.
        val scanned = wm?.scanResults
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
            .orEmpty()
            .toMutableList()

        // Scan results are gated behind location services being on, but the network we are
        // actually joined to is not something the driver should have to take on faith. If the
        // scan did not list it, add it so the pane always shows the connection it is reporting.
        connectedSsid?.let { ssid ->
            if (scanned.none { it.ssid == ssid }) {
                scanned.add(0, WifiNetwork(ssid, secured = true, signal = 3))
            }
        }
        if (_networks != scanned) _networks = scanned

        val configured = runCatching { wm?.configuredNetworks.orEmpty() }.getOrDefault(emptyList())

        // A configuration the platform has disabled for bad credentials is not a saved network in
        // any sense the driver would recognise, so drop it rather than list it as "Saved".
        pending?.let { join ->
            val bad = configured.firstOrNull {
                it.networkId == join.networkId && isDisabledByBadCredentials(it)
            }
            if (bad != null) {
                failPendingJoin("Wrong password")
                return
            }
        }

        val names = configured.mapNotNull { it.SSID?.trim('"')?.ifBlank { null } }.distinct()
        if (_known != names) _known = names
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
        if (!applied && !ConnPolicy.directOnly) openSystemWifiPicker()
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
     *
     * The configuration added here is provisional until the association completes — see
     * [PendingJoin]. Nothing else in the app may treat it as saved before then.
     */
    override fun connect(ssid: String, password: String?) {
        lastError = null
        dropPendingJoin()

        val networkId = runCatching {
            val cfg = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (password != null) preSharedKey = "\"$password\""
                else allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
            val id = wm?.addNetwork(cfg) ?: -1
            if (id == -1) return@runCatching -1
            wm?.enableNetwork(id, true)
            wm?.reconnect()
            id
        }.getOrDefault(-1)

        if (networkId == -1) {
            if (!ConnPolicy.directOnly) openSystemWifiPicker()
            refresh()
            return
        }

        // Only a network that needed a passphrase can fail on one; an open network that simply
        // never associates is a different problem and should not be reported as a bad password.
        if (password != null) {
            pending = PendingJoin(ssid, networkId)
            // Some failures are silent — the supplicant retries and no error extra ever arrives.
            // Without a deadline the provisional config would quietly become permanent.
            handler.postAtTime(
                { if (pending?.networkId == networkId) failPendingJoin("Could not connect") },
                TIMEOUT_TOKEN,
                android.os.SystemClock.uptimeMillis() + JOIN_TIMEOUT_MS,
            )
        }
        refresh()
    }

    private fun dropPendingJoin() {
        pending = null
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
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
        if (pending?.ssid == ssid) dropPendingJoin()
        runCatching {
            wm?.configuredNetworks?.filter { it.SSID?.trim('"') == ssid }?.forEach {
                wm?.removeNetwork(it.networkId)
            }
            wm?.saveConfiguration()
        }
        if (connectedSsid == ssid) connectedSsid = null
        refresh()
    }

    private companion object {
        /**
         * `NetworkSelectionStatus` disable reasons that mean "the credentials were refused",
         * read by name because the constants are @hide. Same set AOSP's WifiTrackerLib checks.
         */
        val AUTH_DISABLE_REASONS = listOf(
            "DISABLED_AUTHENTICATION_FAILURE",
            "DISABLED_BY_WRONG_PASSWORD",
            "DISABLED_AUTHENTICATION_NO_CREDENTIALS",
        )

        /** Long enough for DHCP on a slow AP, short enough that a failure is still felt as one. */
        const val JOIN_TIMEOUT_MS = 25_000L

        /** A scan that has produced nothing by now is not going to. */
        const val SCAN_TIMEOUT_MS = 12_000L

        val TIMEOUT_TOKEN = Any()
        val SCAN_TOKEN = Any()
    }
}
