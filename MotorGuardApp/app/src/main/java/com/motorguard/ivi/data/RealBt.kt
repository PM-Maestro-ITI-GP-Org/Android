@file:Suppress("DEPRECATION")

package com.motorguard.ivi.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Real Bluetooth via BluetoothAdapter. Enable/disable + reading bonded devices work on a
 * privileged platform build. Connecting/unpairing a specific device needs profile proxies
 * / hidden APIs — left as TODO to finish on real hardware (the emulator has no BT).
 */
@SuppressLint("MissingPermission")
class RealBtRepo(context: Context) : BtRepo {

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

    // TODO(on-device): connect/disconnect a specific device via A2DP/Headset profile proxies.
    override fun toggleConnect(name: String) {
        connectedName = if (connectedName == name) null else name
    }

    // TODO(on-device): BluetoothDevice.removeBond() is a hidden API — call via reflection.
    override fun unpair(name: String) {
        _paired.removeAll { it.name == name }
        if (connectedName == name) connectedName = null
    }

    override fun rename(oldName: String, newName: String) {
        val device = adapter?.bondedDevices?.firstOrNull { safeName(it) == oldName }
        runCatching { device?.setAlias(newName) } // setAlias is public since API 33
        refresh()
    }
}
