package com.motorguard.ivi.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Calls straight off the Bluetooth hands-free link, without Telecom.
 *
 * WHY NOT InCallService
 * ---------------------
 * The tidy way to see a call is to be the default dialer and let the platform bind an
 * `InCallService`. That is impossible on this board:
 *
 *     $ cmd role add-role-holder android.app.role.DIALER com.motorguard.ivi
 *     E RoleControllerServiceImpl: Role is unavailable: android.app.role.DIALER
 *     $ pm list features | grep telephony
 *     (nothing)
 *
 * AOSP gates the DIALER role behind telephony hardware, and a Raspberry Pi has no modem,
 * so the role is not merely ungranted — it is not offered. `cmd telecom get-default-dialer`
 * returns null and Telecom's in-call service map is empty. This is not specific to us:
 * com.android.car.dialer declares its InCallService identically and is equally unbound, so
 * *no* app can show a call UI here through Telecom.
 *
 * The head unit is the hands-free unit and the call lives on the phone, so the honest
 * source is the HFP client itself. [BluetoothHeadsetClient] broadcasts every call state
 * change with the number attached, and — the part that matters for being able to speak —
 * owns the SCO link that carries the car's microphone to the phone.
 *
 * REFLECTION
 * ----------
 * `BluetoothHeadsetClient` and `BluetoothHeadsetClientCall` are @SystemApi: real, stable,
 * and usable by a platform-signed privileged app holding BLUETOOTH_PRIVILEGED (which this
 * app is and does), but absent from the public SDK the app compiles against. Reflection is
 * the only way to name them from a Gradle build. Every lookup is wrapped: on an image
 * without the HFP client profile this class simply reports no calls rather than throwing
 * inside the launcher.
 */
class HfpCallSource(private val app: Context) {

    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _call = MutableStateFlow<ActiveCall?>(null)
    val call: StateFlow<ActiveCall?> = _call.asStateFlow()

    /** The HEADSET_CLIENT proxy, once the profile has connected. */
    @Volatile
    private var proxy: BluetoothProfile? = null

    /** The live platform call object, kept so it can be handed back to terminateCall. */
    @Volatile
    private var currentCall: Any? = null

    private var answeredAtElapsedMs = 0L

    /** True once SCO is up, i.e. the car's microphone is actually reaching the phone. */
    private val _audioRouted = MutableStateFlow(false)
    val audioRouted: StateFlow<Boolean> = _audioRouted.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CALL_CHANGED -> onCallChanged(intent)
                ACTION_AUDIO_STATE_CHANGED -> {
                    val state = intent.getIntExtra(EXTRA_STATE, -1)
                    _audioRouted.value = state == STATE_AUDIO_CONNECTED
                    Log.i(TAG, "HFP audio state=$state routed=${_audioRouted.value}")
                }
            }
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_CALL_CHANGED)
                    addAction(ACTION_AUDIO_STATE_CHANGED)
                },
                // EXPORTED, not NOT_EXPORTED. These come from com.android.bluetooth, a
                // different uid, and NOT_EXPORTED restricts delivery to broadcasts sent by
                // this app itself -- so the filter matched, the receiver registered, and
                // every call event was silently dropped. Both are protected broadcasts
                // that only the system may send, so exporting grants nothing to anyone else.
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "HFP receiver not registered", it) }

        // The proxy arrives asynchronously; until then calls are still observed through the
        // broadcasts above, we simply cannot act on them yet.
        runCatching {
            adapter?.getProfileProxy(
                app,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
                        if (profile == PROFILE_HEADSET_CLIENT) {
                            proxy = p
                            Log.i(TAG, "HFP client proxy connected")
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == PROFILE_HEADSET_CLIENT) proxy = null
                    }
                },
                PROFILE_HEADSET_CLIENT,
            )
        }.onFailure { Log.w(TAG, "HFP client proxy unavailable", it) }
    }

    // ---------------------------------------------------------------- events

    private fun onCallChanged(intent: Intent) {
        @Suppress("DEPRECATION")
        val platformCall = intent.getParcelableExtra<android.os.Parcelable>(EXTRA_CALL) ?: run {
            clear()
            return
        }

        val state = invokeInt(platformCall, "getState") ?: return
        val number = invokeString(platformCall, "getNumber").orEmpty()
        val outgoing = invokeBoolean(platformCall, "isOutgoing") ?: false
        Log.i(TAG, "HFP call state=$state outgoing=$outgoing")

        if (state == CALL_STATE_TERMINATED) {
            clear()
            return
        }
        currentCall = platformCall

        val domainState = when (state) {
            CALL_STATE_ACTIVE -> CallState.ACTIVE
            CALL_STATE_HELD, CALL_STATE_HELD_BY_RESPONSE_AND_HOLD -> CallState.HOLDING
            CALL_STATE_DIALING, CALL_STATE_ALERTING -> CallState.DIALING
            CALL_STATE_INCOMING, CALL_STATE_WAITING -> CallState.RINGING
            else -> return
        }

        if (domainState == CallState.ACTIVE) {
            if (answeredAtElapsedMs == 0L) answeredAtElapsedMs = SystemClock.elapsedRealtime()
            // The whole point: once the call is up, bring the SCO link up too, or the
            // driver can hear the caller through the phone but the phone cannot hear them.
            routeAudioToPhone()
        }

        _call.value = ActiveCall(
            number = number,
            // Left null deliberately — PhoneRepository resolves it against the synced
            // phonebook, which knows names the AG never sends over HFP.
            name = null,
            state = domainState,
            direction = if (outgoing) CallDirection.OUTGOING else CallDirection.INCOMING,
            startedAtElapsedMs = answeredAtElapsedMs,
        )
    }

    private fun clear() {
        answeredAtElapsedMs = 0L
        currentCall = null
        _call.value = null
        _audioRouted.value = false
    }

    // ---------------------------------------------------------------- commands

    fun answer() {
        val device = connectedDevice() ?: return
        // Flag 0 = accept the incoming call, holding nothing.
        if (!invokeVoid(proxy, "acceptCall", arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!), arrayOf(device, 0))) {
            Log.w(TAG, "acceptCall failed")
        }
    }

    fun reject() {
        val device = connectedDevice() ?: return
        invokeVoid(proxy, "rejectCall", arrayOf(BluetoothDevice::class.java), arrayOf(device))
    }

    fun hangUp() {
        val device = connectedDevice() ?: return
        val live = currentCall
        if (live != null) {
            val callClass = runCatching { Class.forName(CALL_CLASS) }.getOrNull()
            if (callClass != null &&
                invokeVoid(proxy, "terminateCall", arrayOf(BluetoothDevice::class.java, callClass), arrayOf(device, live))
            ) {
                return
            }
        }
        // A ringing call that was never answered is rejected, not terminated.
        invokeVoid(proxy, "rejectCall", arrayOf(BluetoothDevice::class.java), arrayOf(device))
    }

    /**
     * Bring up the SCO link, which is what actually carries the car's microphone to the
     * phone and the caller's voice to the car's speakers.
     *
     * Safe to call repeatedly: the stack ignores a connect on an already-connected link,
     * and the audio-state broadcast is the authority on whether it took.
     */
    fun routeAudioToPhone() {
        val device = connectedDevice() ?: return
        if (!invokeVoid(proxy, "connectAudio", arrayOf(BluetoothDevice::class.java), arrayOf(device))) {
            Log.w(TAG, "connectAudio failed — the driver will not be heard")
        }
    }

    fun stopRoutingAudio() {
        val device = connectedDevice() ?: return
        invokeVoid(proxy, "disconnectAudio", arrayOf(BluetoothDevice::class.java), arrayOf(device))
    }

    private fun connectedDevice(): BluetoothDevice? = runCatching {
        proxy?.connectedDevices?.firstOrNull()
    }.getOrNull()

    // ---------------------------------------------------------------- reflection

    private fun invokeInt(target: Any, name: String): Int? =
        runCatching { target.javaClass.getMethod(name).invoke(target) as? Int }.getOrNull()

    private fun invokeString(target: Any, name: String): String? =
        runCatching { target.javaClass.getMethod(name).invoke(target) as? String }.getOrNull()

    private fun invokeBoolean(target: Any, name: String): Boolean? =
        runCatching { target.javaClass.getMethod(name).invoke(target) as? Boolean }.getOrNull()

    /** @return true when the call was actually dispatched. */
    private fun invokeVoid(
        target: Any?,
        name: String,
        types: Array<Class<*>>,
        args: Array<Any>,
    ): Boolean {
        val receiverObj = target ?: return false
        return runCatching {
            receiverObj.javaClass.getMethod(name, *types).invoke(receiverObj, *args)
            true
        }.getOrElse {
            Log.w(TAG, "$name unavailable on this image", it)
            false
        }
    }

    private companion object {
        const val TAG = "MotorGuardPhone"

        /** BluetoothProfile.HEADSET_CLIENT — not a public SDK constant. */
        const val PROFILE_HEADSET_CLIENT = 16

        const val ACTION_CALL_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
        const val ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
        const val EXTRA_CALL = "android.bluetooth.headsetclient.extra.CALL"
        const val EXTRA_STATE = "android.bluetooth.profile.extra.STATE"
        const val CALL_CLASS = "android.bluetooth.BluetoothHeadsetClientCall"

        const val STATE_AUDIO_CONNECTED = 2

        // BluetoothHeadsetClientCall states.
        const val CALL_STATE_ACTIVE = 0
        const val CALL_STATE_HELD = 1
        const val CALL_STATE_DIALING = 2
        const val CALL_STATE_ALERTING = 3
        const val CALL_STATE_INCOMING = 4
        const val CALL_STATE_WAITING = 5
        const val CALL_STATE_HELD_BY_RESPONSE_AND_HOLD = 6
        const val CALL_STATE_TERMINATED = 7
    }
}
