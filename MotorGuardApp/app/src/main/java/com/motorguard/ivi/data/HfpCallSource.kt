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
            // Everything below this line runs on the launcher's main thread, unmarshals a
            // platform Parcelable out of the intent and calls @SystemApi methods by
            // reflection. An exception escaping onReceive reaches ActivityThread and kills
            // the process -- so a call arriving would take the whole head unit down instead
            // of drawing the in-call screen. A call we cannot read is a call we do not show;
            // it is never a crash.
            runCatching {
                when (intent?.action) {
                    ACTION_CALL_CHANGED -> onCallChanged(intent)
                    ACTION_AUDIO_STATE_CHANGED -> {
                        val state = intent.getIntExtra(EXTRA_STATE, -1)
                        _audioRouted.value = state == STATE_AUDIO_CONNECTED
                        Log.i(TAG, "HFP audio state=$state routed=${_audioRouted.value}")
                    }
                    // A String subject can never be proven exhaustive, so the in-tree kotlinc
                    // (stricter here than the Gradle build this branch was authored against)
                    // requires this else even though every actual broadcast this receiver is
                    // registered for is one of the two cases above.
                    else -> Unit
                }
            }.onFailure { Log.e(TAG, "HFP broadcast ${intent?.action} not handled", it) }
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
        // The broadcast carries the call, but the proxy is the authority. Reading the extra
        // needs android.bluetooth.BluetoothHeadsetClientCall, a platform class this app never
        // links against, and a failure there must not be mistaken for "the call ended".
        val platformCall = callExtra(intent) ?: liveCallFromProxy()
        if (platformCall == null) {
            Log.i(TAG, "AG_CALL_CHANGED with no readable call, and none live on the link")
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

        if (domainState == CallState.ACTIVE && answeredAtElapsedMs == 0L) {
            answeredAtElapsedMs = SystemClock.elapsedRealtime()
        }

        // SCO carries more than the conversation. Ringback on an outgoing call and the
        // phone's own in-band ringtone on an incoming one both arrive over it, so waiting
        // for CALL_STATE_ACTIVE to raise the link meant every call rang in silence and only
        // found its voice once it was answered. Raising it as soon as there is a call is
        // safe: the stack ignores a connect on a link that is already up, and the
        // audio-state broadcast stays the authority on whether it took.
        routeAudioToPhone()

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

    /**
     * The call object out of the broadcast.
     *
     * Unmarshalling it instantiates `BluetoothHeadsetClientCall`, which lives in the platform
     * (the Bluetooth APEX) rather than in this APK. When that resolution fails the framework
     * raises a `BadParcelableException` from inside `getParcelableExtra` — on the main thread,
     * inside a receiver, which is fatal to the process. Guarded, with [liveCallFromProxy] to
     * fall back on, so an unreadable extra costs a log line instead of the launcher.
     */
    private fun callExtra(intent: Intent): Any? = runCatching {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<android.os.Parcelable>(EXTRA_CALL)
    }.getOrElse {
        Log.w(TAG, "call object in AG_CALL_CHANGED could not be read", it)
        null
    }

    /**
     * The live call read straight off the profile.
     *
     * `getCurrentCalls` is the same state the broadcast was announcing, so asking for it after
     * an unreadable extra still gets the in-call screen up rather than dropping the event.
     */
    private fun liveCallFromProxy(): Any? {
        val device = connectedDevice() ?: return null
        val active = proxy ?: return null
        return runCatching {
            val calls = active.javaClass
                .getMethod("getCurrentCalls", BluetoothDevice::class.java)
                .invoke(active, device) as? List<*>
            calls?.firstOrNull { it != null && invokeInt(it, "getState") != CALL_STATE_TERMINATED }
        }.getOrElse {
            Log.w(TAG, "getCurrentCalls unavailable on this image", it)
            null
        }
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

    /**
     * End the call, whichever kind of ending it is.
     *
     * A call that is still ringing is *rejected*: AT+CHUP applies to a call that has been
     * established, and the stack refuses `terminateCall` for one that has not. Decline
     * therefore did nothing at all until the driver had answered first — the refusal was
     * read as a success (see [invokeVoid]) and the reject below was never reached.
     */
    fun hangUp() {
        val device = connectedDevice() ?: run {
            Log.w(TAG, "no hands-free device to hang up on")
            return
        }
        val live = currentCall
        val state = live?.let { invokeInt(it, "getState") }
        val unanswered = state == CALL_STATE_INCOMING || state == CALL_STATE_WAITING

        if (!unanswered && live != null) {
            val callClass = runCatching { Class.forName(CALL_CLASS) }.getOrNull()
            if (callClass != null &&
                invokeVoid(proxy, "terminateCall", arrayOf(BluetoothDevice::class.java, callClass), arrayOf(device, live))
            ) {
                return
            }
        }
        if (!invokeVoid(proxy, "rejectCall", arrayOf(BluetoothDevice::class.java), arrayOf(device))) {
            Log.w(TAG, "the link accepted neither terminateCall nor rejectCall")
        }
    }

    /**
     * Hold the active call, or bring the held one back.
     *
     * Both directions are AT+CHLD=2 on the wire; AOSP's own HfpClientConnection spells them
     * `holdCall` and `acceptCall(CALL_ACCEPT_HOLD)`, and this follows it exactly. Without
     * this the Hold button reached [InCallBridge], which has no service bound on a board
     * with no DIALER role, and did nothing at all.
     */
    fun setOnHold(hold: Boolean) {
        val device = connectedDevice() ?: return
        val dispatched = if (hold) {
            invokeVoid(proxy, "holdCall", arrayOf(BluetoothDevice::class.java), arrayOf(device))
        } else {
            invokeVoid(
                proxy,
                "acceptCall",
                arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!),
                arrayOf(device, CALL_ACCEPT_HOLD),
            )
        }
        if (!dispatched) Log.w(TAG, "hold toggle unavailable on this image")
    }

    /** In-call DTMF. Sent to the phone as AT+VTS, which plays it into the call for us. */
    fun sendDtmf(digit: Char) {
        val device = connectedDevice() ?: return
        invokeVoid(
            proxy,
            "sendDTMF",
            arrayOf(BluetoothDevice::class.java, Byte::class.javaPrimitiveType!!),
            arrayOf(device, digit.code.toByte()),
        )
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
        // connectAudio took no arguments before the profile was reworked and takes the device
        // after it. Try the modern shape, then the old one, rather than assume the image --
        // guessing wrong here is a call with no audio in either direction.
        val raised =
            invokeVoid(proxy, "connectAudio", arrayOf(BluetoothDevice::class.java), arrayOf(device)) ||
                invokeVoid(proxy, "connectAudio", emptyArray(), emptyArray())
        if (!raised) Log.w(TAG, "connectAudio refused — the call will have no audio")
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

    /**
     * @return true when the profile **accepted** the command — not merely that the reflective
     * call did not throw.
     *
     * Every one of these methods answers with a boolean for "the stack took it", and throwing
     * that away is what broke Decline: `terminateCall` refuses a call that is still ringing,
     * returned false, and the caller read the dispatch as a success and never reached its
     * `rejectCall` fallback. The same blindness would hide a refused `connectAudio`, which is
     * a call with no sound in it.
     */
    private fun invokeVoid(
        target: Any?,
        name: String,
        types: Array<Class<*>>,
        args: Array<Any>,
    ): Boolean {
        val receiverObj = target ?: return false
        return runCatching {
            val answer = receiverObj.javaClass.getMethod(name, *types).invoke(receiverObj, *args)
            // A void method comes back as null; only an explicit false is a refusal.
            answer !is Boolean || answer
        }.getOrElse {
            Log.w(TAG, "$name unavailable on this image", it)
            false
        }
    }

    private companion object {
        const val TAG = "MotorGuardPhone"

        /** BluetoothProfile.HEADSET_CLIENT — not a public SDK constant. */
        const val PROFILE_HEADSET_CLIENT = 16

        /** BluetoothHeadsetClient.CALL_ACCEPT_HOLD — resume the call that is on hold. */
        const val CALL_ACCEPT_HOLD = 1

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
