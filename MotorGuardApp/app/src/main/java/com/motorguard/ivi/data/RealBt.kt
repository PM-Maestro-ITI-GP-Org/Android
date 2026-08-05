@file:Suppress("DEPRECATION")

package com.motorguard.ivi.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Real Bluetooth via BluetoothAdapter, written for a head unit rather than a phone.
 *
 * The car is the **A2DP sink**: the phone holds the music and the car renders it. That is why
 * [toggleConnect] drives `A2DP_SINK` and `HEADSET_CLIENT` and not `A2DP`/`HEADSET` — the latter
 * are the source/gateway roles a phone plays, and asking for them on a head unit connects
 * nothing. The Pi image has `A2dpSinkService` and `AvrcpControllerService` enabled, so the
 * sink-side profiles are really there to be driven.
 *
 * Reading state and bonded devices needs only the runtime BLUETOOTH_CONNECT permission, and
 * scanning needs BLUETOOTH_SCAN. The control paths — enable/disable, connect, unpair, forcing
 * scan mode — go through `@SystemApi` or hidden methods, so they take effect on the
 * platform-signed build (BLUETOOTH_PRIVILEGED, see the privapp allow-list) and are swallowed
 * everywhere else. Every one of them is wrapped, because "this build cannot do that" must never
 * present as a crash.
 */
@SuppressLint("MissingPermission")
class RealBtRepo(context: Context) : BtRepo {

    private val appContext = context.applicationContext
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())

    private var _enabled by mutableStateOf(adapter?.isEnabled == true)
    override val enabled: Boolean get() = _enabled

    override var connectedName by mutableStateOf<String?>(null)
        private set

    override var activity by mutableStateOf<BtActivity>(BtActivity.Idle)
        private set

    override var discoverable by mutableStateOf(isDiscoverableNow())
        private set

    override val localName: String
        get() = runCatching { adapter?.name }.getOrNull() ?: "Motor Guard"

    /**
     * Whole-list state rather than a [androidx.compose.runtime.mutableStateListOf].
     *
     * A SnapshotStateList notifies on every mutation, so the old `clear()` + re-add was two
     * observable writes with an empty list in between, and Compose could recompose against that
     * empty middle. A connected phone emits ACL and bond broadcasts in bursts, each one calling
     * [refresh] — which is why the paired list visibly flickered on the Pi. Assigning a finished
     * list once cannot be observed half-applied.
     */
    private var _paired by mutableStateOf<List<BtDevice>>(emptyList())
    override val paired: List<BtDevice> get() = _paired

    private var _discovered by mutableStateOf<List<BtDevice>>(emptyList())
    override val discovered: List<BtDevice> get() = _discovered

    /** Cleared when a scan starts, so a stale device does not linger across scans. */
    private val seen = linkedMapOf<String, BtDevice>()

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = handle(intent)
        }, filter)
        refresh()
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                seen.clear()
                _discovered = emptyList()
                activity = BtActivity.Scanning
            }

            BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                if (activity is BtActivity.Scanning) activity = BtActivity.Idle

            // ACTION_FOUND fires per device per scan; NAME_CHANGED follows later for devices
            // that answered the name request only after being found, which is why both feed the
            // same map instead of the found-list being built once.
            BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED ->
                device(intent)?.let { remember(it) }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                connectedName = deviceName(intent)
                activity = BtActivity.Idle
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                if (connectedName == deviceName(intent)) connectedName = null

            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val name = deviceName(intent).orEmpty()
                when (state) {
                    BluetoothDevice.BOND_BONDED ->
                        if (activity is BtActivity.Pairing) activity = BtActivity.Idle
                    // BOND_NONE arriving while we are pairing means the phone rejected or the
                    // passkey was dismissed — the one failure the driver most needs told about.
                    BluetoothDevice.BOND_NONE ->
                        if (activity is BtActivity.Pairing) {
                            activity = BtActivity.Failed(name, "Pairing failed or was cancelled")
                        }
                }
            }

            BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> discoverable = isDiscoverableNow()
        }
        refresh()
    }

    private fun device(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun deviceName(intent: Intent): String? = device(intent)?.let { displayName(it) }

    /** Never blank: an unnamed device still has to be tappable, so it shows its address. */
    private fun displayName(d: BluetoothDevice): String =
        runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address

    private fun remember(d: BluetoothDevice) {
        val bonded = runCatching { d.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        // Bonded devices belong in the paired list, which refresh() builds from the adapter;
        // listing them under "Available" as well would offer to pair what is already paired.
        if (bonded) return
        seen[d.address] = BtDevice(d.address, displayName(d), kindOf(d), bonded = false)
        _discovered = seen.values.sortedBy { it.name }
    }

    private fun refresh() {
        _enabled = adapter?.isEnabled == true
        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .map { d -> BtDevice(d.address, displayName(d), kindOf(d), bonded = true) }
            // bondedDevices has no defined order, so it can come back permuted between calls and
            // rewrite every row for no reason. A stable order makes an unchanged set compare equal.
            .sortedBy { it.name }
        // Bursts of ACL broadcasts mostly say nothing new; assigning only on a real change keeps
        // them from recomposing the pane.
        if (_paired != bonded) _paired = bonded

        val bondedAddresses = bonded.mapTo(mutableSetOf()) { it.address }
        if (_discovered.any { it.address in bondedAddresses }) {
            seen.keys.removeAll(bondedAddresses)
            _discovered = seen.values.sortedBy { it.name }
        }
    }

    private fun kindOf(d: BluetoothDevice): BtKind =
        when (runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()) {
            BluetoothClass.Device.Major.PHONE -> BtKind.PHONE
            BluetoothClass.Device.Major.AUDIO_VIDEO -> BtKind.AUDIO
            BluetoothClass.Device.Major.WEARABLE -> BtKind.WEARABLE
            else -> BtKind.AUDIO
        }

    /**
     * Turn the radio on or off.
     *
     * `BluetoothAdapter.enable()/disable()` were deprecated in API 33 and simply return false for
     * anything that is not a system app — which is why this switch appeared to do nothing on a
     * phone. The direct call is still tried first (it works on the platform build), and when it
     * is refused the system is asked instead: ACTION_REQUEST_ENABLE shows the standard "allow
     * Bluetooth?" dialog, and for switching off there is no such intent, so the Bluetooth
     * settings screen is opened.
     */
    override fun setEnabled(enabled: Boolean) {
        val applied = runCatching {
            if (enabled) adapter?.enable() == true else adapter?.disable() == true
        }.getOrDefault(false)
        if (applied || ConnPolicy.directOnly) return

        val intent = if (enabled) {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        } else {
            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        runCatching {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Start a classic-Bluetooth inquiry.
     *
     * Discovery has to be cancelled first: a second `startDiscovery` while one is already running
     * returns false and quietly does nothing, which would leave the pane spinning forever. The
     * status flips to [BtActivity.Scanning] from the DISCOVERY_STARTED broadcast rather than
     * here, so what the driver sees is what the radio is actually doing.
     */
    override fun startScan() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        runCatching {
            if (a.isDiscovering) a.cancelDiscovery()
            if (!a.startDiscovery()) {
                activity = BtActivity.Failed("", "Cannot scan — check Bluetooth permissions")
            }
        }.onFailure {
            activity = BtActivity.Failed("", "Cannot scan — check Bluetooth permissions")
        }
    }

    override fun stopScan() {
        runCatching { adapter?.cancelDiscovery() }
        if (activity is BtActivity.Scanning) activity = BtActivity.Idle
    }

    /**
     * Make the car findable by a phone — the direction that matters on a head unit.
     *
     * `setScanMode` is `@SystemApi`, so it is reached reflectively and works on the
     * platform-signed image. Its signature gained a timeout parameter and later lost it again
     * across releases, so both are tried before falling back to ACTION_REQUEST_DISCOVERABLE,
     * which is all an unprivileged build can do.
     */
    override fun requestDiscoverable(on: Boolean) {
        val a = adapter ?: return
        val mode = if (on) {
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        } else {
            BluetoothAdapter.SCAN_MODE_CONNECTABLE
        }

        val applied = runCatching {
            val m = runCatching {
                a.javaClass.getMethod("setScanMode", Int::class.java, Long::class.java)
            }.getOrNull()
            if (m != null) {
                m.invoke(a, mode, DISCOVERABLE_SECONDS * 1000L)
            } else {
                a.javaClass.getMethod("setScanMode", Int::class.java).invoke(a, mode)
            }
            true
        }.getOrDefault(false)

        if (applied) {
            discoverable = isDiscoverableNow()
            // The platform drops back to CONNECTABLE on its own when the window expires, but no
            // broadcast is guaranteed for it, so the flag is re-read at the deadline too.
            if (on) main.postDelayed({ discoverable = isDiscoverableNow() }, DISCOVERABLE_SECONDS * 1000L)
            return
        }
        if (ConnPolicy.directOnly || !on) return

        runCatching {
            appContext.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun isDiscoverableNow(): Boolean = runCatching {
        adapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
    }.getOrDefault(false)

    /**
     * Begin pairing with a device found by a scan.
     *
     * `createBond` shows the system passkey dialog, which is the one part of pairing an app is
     * not allowed to draw itself. Discovery is cancelled first because an inquiry in progress
     * starves the pairing exchange of radio time and is the usual cause of a pairing that hangs.
     */
    override fun pair(address: String) {
        val device = remoteDevice(address) ?: return
        runCatching { adapter?.cancelDiscovery() }
        activity = BtActivity.Pairing(displayName(device))
        val started = runCatching { device.createBond() }.getOrDefault(false)
        if (!started) activity = BtActivity.Failed(displayName(device), "Could not start pairing")
    }

    /**
     * Connect or disconnect a bonded device on the car's own profiles.
     *
     * A2DP_SINK receives the phone's audio and HEADSET_CLIENT is the hands-free side, so a phone
     * gets both music and calls rather than half a connection. Both are `@SystemApi` and hidden
     * from the SDK, so they are reached reflectively through a profile proxy; on the
     * platform-signed build with BLUETOOTH_PRIVILEGED they work, anywhere else the call throws
     * and is swallowed. The optimistic state update is what keeps the UI honest in the meantime:
     * the ACL broadcast corrects it either way, so a refused connect goes back on its own.
     */
    override fun toggleConnect(address: String) {
        val device = remoteDevice(address) ?: return
        val name = displayName(device)
        val disconnecting = connectedName == name
        connectedName = if (disconnecting) null else name
        activity = if (disconnecting) BtActivity.Idle else BtActivity.Connecting(name)

        listOf(PROFILE_A2DP_SINK, PROFILE_HEADSET_CLIENT).forEach { profile ->
            withProxy(profile) { proxy ->
                val method = if (disconnecting) "disconnect" else "connect"
                proxy.javaClass
                    .getMethod(method, BluetoothDevice::class.java)
                    .invoke(proxy, device)
            }
        }
        // Nothing guarantees an ACL broadcast for a connect the stack silently refused, so the
        // spinner is given a deadline rather than being left to run forever.
        if (!disconnecting) {
            main.postDelayed(
                {
                    if (activity is BtActivity.Connecting && connectedName != name) {
                        activity = BtActivity.Failed(name, "Could not connect")
                    } else if (activity is BtActivity.Connecting) {
                        activity = BtActivity.Idle
                    }
                },
                CONNECT_TIMEOUT_MS,
            )
        }
    }

    /**
     * Forget a device. `removeBond()` is hidden, so it goes through reflection; the bond-state
     * broadcast registered above is what removes the row once the platform agrees.
     */
    override fun unpair(address: String) {
        val device = remoteDevice(address)
        runCatching {
            device?.javaClass?.getMethod("removeBond")?.invoke(device)
        }
        // Drop it locally too: on an unprivileged build removeBond throws and no broadcast is
        // coming, and a "forget" that visibly does nothing is worse than one that is optimistic.
        val name = _paired.firstOrNull { it.address == address }?.name
        _paired = _paired.filterNot { it.address == address }
        if (connectedName != null && connectedName == name) connectedName = null
    }

    override fun rename(address: String, newName: String) {
        val device = remoteDevice(address)
        runCatching { device?.setAlias(newName) } // setAlias is public since API 33
        refresh()
    }

    /** Bonded devices are the common case, so they answer without going back to the adapter. */
    private fun remoteDevice(address: String): BluetoothDevice? =
        runCatching {
            adapter?.bondedDevices?.firstOrNull { it.address == address }
                ?: adapter?.getRemoteDevice(address)
        }.getOrNull()

    /**
     * Run [block] against a profile proxy, then hand the proxy straight back — these are a
     * shared system resource and leaking one starves the next caller.
     */
    private fun withProxy(profile: Int, block: (android.bluetooth.BluetoothProfile) -> Unit) {
        runCatching {
            adapter?.getProfileProxy(
                appContext,
                object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(
                        p: Int,
                        proxy: android.bluetooth.BluetoothProfile,
                    ) {
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

    private companion object {
        /**
         * `BluetoothProfile.A2DP_SINK` and `HEADSET_CLIENT` are `@hide`, so the constants cannot
         * be referenced by name from an app compiled against the public SDK. The values are part
         * of the stable AIDL surface and have not moved since they were introduced.
         */
        const val PROFILE_A2DP_SINK = 11
        const val PROFILE_HEADSET_CLIENT = 16

        /** Long enough to find the car on a phone, short enough not to advertise all day. */
        const val DISCOVERABLE_SECONDS = 300

        const val CONNECT_TIMEOUT_MS = 12_000L
    }
}
