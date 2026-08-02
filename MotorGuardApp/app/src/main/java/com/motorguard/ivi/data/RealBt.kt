@file:Suppress("DEPRECATION")

package com.motorguard.ivi.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Real Bluetooth via BluetoothAdapter.
 *
 * Reading state and bonded devices needs only the runtime BLUETOOTH_CONNECT permission. The
 * control paths — enable/disable, connect/disconnect, unpair — go through `@SystemApi` or hidden
 * methods, so they take effect on the platform-signed build (BLUETOOTH_PRIVILEGED, see the
 * privapp allow-list) and are swallowed everywhere else. Every one of them is wrapped, because
 * "this build cannot do that" must never present as a crash.
 *
 * Pairing a *new* device is deliberately absent: it needs the system pairing dialog to show a
 * passkey, which an app cannot draw. Settings' role here is managing devices already bonded.
 */
@SuppressLint("MissingPermission")
class RealBtRepo(context: Context) : BtRepo {

    private val appContext = context.applicationContext
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var _enabled by mutableStateOf(adapter?.isEnabled == true)
    override val enabled: Boolean get() = _enabled
    override var connectedName by mutableStateOf<String?>(null)
        private set

    private val _paired = mutableStateListOf<BtDevice>()
    override val paired: List<BtDevice> get() = _paired

    init {
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED ->
                        connectedName = deviceName(intent)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                        if (connectedName == deviceName(intent)) connectedName = null
                }
                refresh()
            }
        }, filter)
        refresh()
    }

    private fun deviceName(intent: Intent): String? =
        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let { safeName(it) }

    private fun safeName(d: BluetoothDevice): String? = runCatching { d.name ?: d.address }.getOrNull()

    private fun refresh() {
        _enabled = adapter?.isEnabled == true
        _paired.clear()
        adapter?.bondedDevices?.forEach { d ->
            val name = safeName(d) ?: return@forEach
            _paired.add(BtDevice(name, kindOf(d)))
        }
    }

    private fun kindOf(d: BluetoothDevice): BtKind = when (d.bluetoothClass?.majorDeviceClass) {
        BluetoothClass.Device.Major.PHONE -> BtKind.PHONE
        BluetoothClass.Device.Major.AUDIO_VIDEO -> BtKind.AUDIO
        BluetoothClass.Device.Major.WEARABLE -> BtKind.WEARABLE
        else -> BtKind.AUDIO
    }

    override fun setEnabled(enabled: Boolean) {
        runCatching { if (enabled) adapter?.enable() else adapter?.disable() }
    }

    /**
     * Connect or disconnect a bonded device over A2DP (and Headset, so a phone gets both audio
     * and calls rather than half a connection).
     *
     * `BluetoothA2dp.connect/disconnect` are `@SystemApi` and hidden from the SDK, so they are
     * reached reflectively through a profile proxy. On the platform-signed build with
     * BLUETOOTH_PRIVILEGED they work; anywhere else the call throws and is swallowed. The
     * optimistic state update below is what keeps the UI honest in the meantime: the ACL
     * broadcast corrects it either way, so a refused connect goes back on its own.
     */
    override fun toggleConnect(name: String) {
        val device = adapter?.bondedDevices?.firstOrNull { safeName(it) == name }
        val disconnecting = connectedName == name
        connectedName = if (disconnecting) null else name
        if (device == null) return
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
            withProxy(profile) { proxy ->
                val method = if (disconnecting) "disconnect" else "connect"
                proxy.javaClass
                    .getMethod(method, BluetoothDevice::class.java)
                    .invoke(proxy, device)
            }
        }
    }

    /**
     * Forget a device. `removeBond()` is hidden, so it goes through reflection; the bond-state
     * broadcast registered above is what removes the row once the platform agrees.
     */
    override fun unpair(name: String) {
        val device = adapter?.bondedDevices?.firstOrNull { safeName(it) == name }
        runCatching {
            device?.javaClass?.getMethod("removeBond")?.invoke(device)
        }
        // Drop it locally too: on an unprivileged build removeBond throws and no broadcast is
        // coming, and a "forget" that visibly does nothing is worse than one that is optimistic.
        _paired.removeAll { it.name == name }
        if (connectedName == name) connectedName = null
    }

    /**
     * Run [block] against a profile proxy, then hand the proxy straight back — these are a
     * shared system resource and leaking one starves the next caller.
     */
    private fun withProxy(profile: Int, block: (BluetoothProfile) -> Unit) {
        runCatching {
            adapter?.getProfileProxy(
                appContext,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        runCatching { block(proxy) }
                        runCatching { adapter.closeProfileProxy(p, proxy) }
                        refresh()
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                },
                profile,
            )
        }
    }

    override fun rename(oldName: String, newName: String) {
        val device = adapter?.bondedDevices?.firstOrNull { safeName(it) == oldName }
        runCatching { device?.setAlias(newName) } // setAlias is public since API 33
        refresh()
    }
}
