package com.motorguard.ivi.data.media

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A phone arriving and leaving, as events.
 *
 * The counterpart to [UsbEvents], and it exists for the same reason: connecting a phone produced
 * no acknowledgement anywhere in the UI. The Bluetooth pane would eventually show the device as
 * connected, but only if the driver happened to be looking at it — and the thing they actually
 * want to know is that the car is now ready to play their music.
 */
@SuppressLint("MissingPermission")
object BtEvents {

    sealed interface Event {
        data class Connected(val name: String) : Event
        data class Disconnected(val name: String) : Event
    }

    fun stream(context: Context): Flow<Event> = callbackFlow {
        val app = context.applicationContext

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val device = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val name = device?.let { displayName(app, it) } ?: return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> trySend(Event.Connected(name))
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> trySend(Event.Disconnected(name))
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        runCatching { app.registerReceiver(receiver, filter) }

        awaitClose { runCatching { app.unregisterReceiver(receiver) } }
    }

    /**
     * Names seen while a device was connected, kept so a *dis*connect can still be named.
     *
     * By the time ACL_DISCONNECTED arrives the stack has often torn the record down and `name`
     * comes back null — which is why the disconnect banner read "54:F2:94:13:AD:6D disconnected"
     * instead of "max_P40 disconnected". A MAC address tells the driver nothing about which
     * phone just dropped.
     */
    private val names = mutableMapOf<String, String>()

    /**
     * Never blank, and never a crash: reading `name` needs BLUETOOTH_CONNECT from API 31, and a
     * device that has not answered its name request yet has only an address to show.
     */
    private fun displayName(context: Context, device: BluetoothDevice): String {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

        val live = if (allowed) {
            runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (live != null) {
            names[device.address] = live
            return live
        }
        // The bonded list usually still knows it even when the device record does not.
        val bonded = if (allowed) {
            runCatching {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                    ?.adapter
                    ?.bondedDevices
                    ?.firstOrNull { it.address == device.address }
                    ?.name
            }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (bonded != null) {
            names[device.address] = bonded
            return bonded
        }
        return names[device.address] ?: device.address
    }
}
