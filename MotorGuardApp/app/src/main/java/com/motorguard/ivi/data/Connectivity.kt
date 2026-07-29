package com.motorguard.ivi.data

import android.content.Context
import com.motorguard.ivi.R

/** Wi-Fi network as shown in Settings. signal is 0..3. */
data class WifiNetwork(val ssid: String, val secured: Boolean, val signal: Int)

enum class BtKind { PHONE, AUDIO, WEARABLE }

data class BtDevice(val name: String, val kind: BtKind)

/**
 * Wi-Fi state + control. Implemented by [MockWifiRepo] (emulator / unprivileged) and
 * [RealWifiRepo] (WifiManager on a platform-signed build). State properties are
 * Compose-observable, so Settings recomposes on change.
 */
interface WifiRepo {
    val enabled: Boolean
    val connectedSsid: String?
    val known: List<String>
    val networks: List<WifiNetwork>
    fun setEnabled(enabled: Boolean)
    fun connect(ssid: String, password: String? = null)
    fun disconnect()
    fun forget(ssid: String)
}

/** Bluetooth state + control. Mock or real (BluetoothAdapter). */
interface BtRepo {
    val enabled: Boolean
    val connectedName: String?
    val paired: List<BtDevice>
    fun setEnabled(enabled: Boolean)
    fun toggleConnect(name: String)
    fun unpair(name: String)
    fun rename(oldName: String, newName: String)
}

/**
 * App-wide connectivity provider. Picks the real system-service implementation when the
 * build enables it (bool/use_real_connectivity — overlaid to true in the platform build),
 * otherwise the mock. Call [init] once from MainActivity.
 */
object Conn {
    lateinit var wifi: WifiRepo
        private set
    lateinit var bt: BtRepo
        private set

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val useReal = context.resources.getBoolean(R.bool.use_real_connectivity)
        val app = context.applicationContext
        wifi = if (useReal) RealWifiRepo(app) else MockWifiRepo()
        bt = if (useReal) RealBtRepo(app) else MockBtRepo()
        initialized = true
    }
}
