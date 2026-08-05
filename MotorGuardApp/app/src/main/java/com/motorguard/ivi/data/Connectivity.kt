package com.motorguard.ivi.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.motorguard.ivi.R

/** Wi-Fi network as shown in Settings. signal is 0..3. */
data class WifiNetwork(val ssid: String, val secured: Boolean, val signal: Int)

enum class BtKind { PHONE, AUDIO, WEARABLE }

/**
 * One Bluetooth device, keyed by [address] rather than by name.
 *
 * Discovery is what forces the address to be the identity: a scan routinely turns up several
 * devices sharing a name ("iPhone"), and some report no name at all until they are queried — so
 * a name-keyed list cannot say which row the driver tapped. [name] falls back to the address
 * when the radio has not produced one yet, so a row is never blank.
 */
data class BtDevice(
    val address: String,
    val name: String,
    val kind: BtKind,
    /** False for a device that only turned up in a scan and has not been paired. */
    val bonded: Boolean = true,
)

/**
 * What the Bluetooth radio is doing right now.
 *
 * The pane had no way to say "scanning" — the list simply sat empty and the driver could not
 * tell a finished, empty scan from one that had not started. Every long-running operation gets
 * a case here so there is always something honest to put on the status line.
 */
sealed interface BtActivity {
    data object Idle : BtActivity
    data object Scanning : BtActivity
    data class Pairing(val name: String) : BtActivity
    data class Connecting(val name: String) : BtActivity
    data class Failed(val name: String, val reason: String) : BtActivity
}

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

    /**
     * Why the last join failed, or null. A wrong password is otherwise invisible: the platform
     * saves the configuration the moment it is added and then quietly disables it, so without
     * this the pane would show the network as "Saved" and the driver would have no idea the
     * password was rejected.
     */
    val lastError: String?

    /**
     * True while a scan is in flight.
     *
     * A Wi-Fi scan takes seconds and produces nothing until it finishes, so without this the
     * pane cannot tell "still looking" from "looked, found nothing" — and both render as an
     * empty list.
     */
    val scanning: Boolean

    /** The network a join is in progress for, or null. */
    val connectingSsid: String?

    fun setEnabled(enabled: Boolean)

    /** Ask for a fresh scan. Results arrive asynchronously into [networks]. */
    fun startScan()

    fun connect(ssid: String, password: String? = null)
    fun disconnect()
    fun forget(ssid: String)
    fun clearError()
}

/**
 * Bluetooth state + control. Mock or real (BluetoothAdapter).
 *
 * The head unit is the **sink**, not the source: the driver's phone connects *to* the car and
 * the car plays its audio. That inverts the usual phone-app assumptions, and two members here
 * exist because of it — [discoverable], since a phone can only pick the car out of its own
 * list if the car is advertising, and [localName], which is the name the driver has to look
 * for on the phone. Outbound connects are still supported ([toggleConnect]) for the case where
 * the phone is already bonded and the driver wants to reconnect from the car side.
 */
interface BtRepo {
    val enabled: Boolean
    val connectedName: String?
    val paired: List<BtDevice>

    /** Nearby devices seen by the current/last scan that are not already bonded. */
    val discovered: List<BtDevice>

    /** Scanning / pairing / connecting, for the pane's status line. */
    val activity: BtActivity

    /** True while the car is advertising itself, so a phone can find it. */
    val discoverable: Boolean

    /** The name the car shows up as on the phone. */
    val localName: String

    fun setEnabled(enabled: Boolean)
    fun startScan()
    fun stopScan()

    /**
     * Advertise the car for a while so a phone can find and pair with it. Not permanent by
     * design — a head unit that is discoverable forever is a standing invitation to anyone in
     * the car park.
     */
    fun requestDiscoverable(on: Boolean)

    fun pair(address: String)
    fun toggleConnect(address: String)
    fun unpair(address: String)
    fun rename(address: String, newName: String)
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
