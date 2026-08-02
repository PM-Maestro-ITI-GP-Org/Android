package com.motorguard.ivi.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the Wi-Fi and Bluetooth control paths should behave when the platform refuses them.
 *
 * The repos always try the direct call first — `setWifiEnabled`, `BluetoothAdapter.enable`,
 * `addNetwork` — which is what a platform-signed build is allowed to do. The question this
 * answers is what happens when that call is refused, and the right answer differs by device:
 *
 *  - On a phone or the emulator, handing off to the system Wi-Fi/Bluetooth screens is the only
 *    way the driver can finish the job, so a refused toggle should open them.
 *  - In the car it is the wrong answer. The head unit is running this app as its launcher; a
 *    stock Settings panel appearing over it is jarring at best, and on a trimmed AAOS image
 *    those activities may not exist at all. There the toggle should simply do what it can and
 *    stay put.
 *
 * Hence [directOnly]: when set, the direct call is the whole story and nothing is handed off.
 * It defaults on for the real/privileged build, since that build is expected to succeed.
 */
object ConnPolicy {

    private var _directOnly by mutableStateOf(false)

    /** True to suppress the system-settings fallback and keep control inside this app. */
    var directOnly: Boolean
        get() = _directOnly
        set(value) {
            _directOnly = value
            LocalStore.putBoolean(LocalStore.Keys.DIRECT_CONTROL, value)
        }

    /**
     * Load the saved choice. [defaultForBuild] is what the build implies — true on the
     * platform-signed image, where the direct calls are expected to work.
     */
    fun restore(defaultForBuild: Boolean) {
        _directOnly = LocalStore.getBoolean(LocalStore.Keys.DIRECT_CONTROL, defaultForBuild)
    }
}
