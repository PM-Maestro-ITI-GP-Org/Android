package com.motorguard.ivi.data

import android.content.Context
import com.motorguard.ivi.R

/** Wi-Fi network as shown in Settings. signal is 0..3. */
data class WifiNetwork(val ssid: String, val secured: Boolean, val signal: Int)

enum class BtKind { PHONE, AUDIO, WEARABLE }

data class BtDevice(val name: String, val kind: BtKind)

/**
 * An Android user (driver profile) as shown in Settings. [id] is the platform user id,
 * [initial] is a single letter for the avatar, [color] 0..n picks an accent from the theme.
 */
data class UserProfile(
    val id: Int,
    val name: String,
    val isActive: Boolean,
    val isGuest: Boolean = false,
    val initial: String = name.trim().firstOrNull()?.uppercase() ?: "?",
    val color: Int = 0,
)

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
 * Driver profiles (multi-user) state + control. Mock or real (UserManager). Listing/reading
 * users works unprivileged; switching/adding/removing users are privileged system APIs, so on
 * an unprivileged build those no-op (see [RealUsersRepo]). State is Compose-observable.
 */
interface UsersRepo {
    val users: List<UserProfile>
    val active: UserProfile?
    fun switchTo(id: Int)
    fun addUser(name: String)
    fun removeUser(id: Int)
    fun addGuest()

    /** Rename a profile. The platform build needs MANAGE_USERS for this; local profiles do not. */
    fun rename(id: Int, newName: String)

    /** Pick the avatar colour, as an index into the theme's accent list. */
    fun setColor(id: Int, color: Int)
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
    lateinit var users: UsersRepo
        private set

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val useReal = context.resources.getBoolean(R.bool.use_real_connectivity)
        val app = context.applicationContext
        wifi = if (useReal) RealWifiRepo(app) else MockWifiRepo()
        bt = if (useReal) RealBtRepo(app) else MockBtRepo()
        users = if (useReal) RealUsersRepo(app) else LocalUsersRepo()
        initialized = true
    }
}
