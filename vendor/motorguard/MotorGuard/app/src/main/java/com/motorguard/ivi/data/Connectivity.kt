package com.motorguard.ivi.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * App-wide connectivity provider. Picks the real system-service implementations when the build
 * enables them (bool/use_real_connectivity — overlaid to true in the platform build), otherwise
 * the mock ones. Call [init] once from MainActivity.
 *
 * [useReal] can also be flipped at runtime from Settings ▸ System, which is what makes the real
 * path testable on a normal device without editing a resource and rebuilding. The repos are held
 * in Compose state so swapping them recomposes whatever is on screen; the choice is saved, so it
 * survives a restart. Reading state always works unprivileged — it is the *control* calls
 * (toggling a radio, unpairing) that need the platform-signed build.
 */
object Conn {

    var wifi: WifiRepo by mutableStateOf(MockWifiRepo())
        private set
    var bt: BtRepo by mutableStateOf(MockBtRepo())
        private set

    /** True when the real WifiManager/BluetoothAdapter implementations are in use. */
    var useReal by mutableStateOf(false)
        private set

    private var initialized = false
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        // The build's answer is the default; a saved override from Settings wins over it.
        val fromBuild = context.resources.getBoolean(R.bool.use_real_connectivity)
        // The platform build is expected to control the radios directly, so it defaults to not
        // handing off to system screens; a phone build defaults to the handoff that works there.
        ConnPolicy.restore(defaultForBuild = fromBuild)
        build(LocalStore.getBoolean(LocalStore.Keys.USE_REAL, fromBuild))
        initialized = true
    }

    /** Switch between the real and mock implementations, and remember the choice. */
    fun switchSource(value: Boolean) {
        if (!initialized || value == useReal) return
        LocalStore.putBoolean(LocalStore.Keys.USE_REAL, value)
        build(value)
    }

    private fun build(real: Boolean) {
        useReal = real
        // Constructing the real repos touches system services and registers receivers, which can
        // throw on a build that is not allowed to — falling back keeps Settings usable instead of
        // taking the app down with it.
        val ok = runCatching {
            if (real) {
                wifi = RealWifiRepo(appContext)
                bt = RealBtRepo(appContext)
            } else {
                wifi = MockWifiRepo()
                bt = MockBtRepo()
            }
        }.isSuccess
        if (!ok && real) {
            useReal = false
            wifi = MockWifiRepo()
            bt = MockBtRepo()
        }
    }
}
