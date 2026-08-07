package com.motorguard.ivi.data

import android.telecom.Call
import android.telecom.InCallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `InCallService` is instantiated by the platform, not by us, so it cannot live inside
 * [TelecomPhoneSource]. This object is the seam: the service pushes the live
 * `android.telecom.Call` and the audio state in here, the repository reads them out.
 *
 * Nothing above the data layer ever sees an `android.telecom.Call`.
 */
object InCallBridge {

    private val _call = MutableStateFlow<Call?>(null)
    private val _muted = MutableStateFlow(false)

    /** Bumped on every `Call.Callback` so collectors re-read a state that mutates in place. */
    private val _revision = MutableStateFlow(0)

    val call: StateFlow<Call?> = _call.asStateFlow()
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Set while the platform has us bound; needed because mute lives on the service. */
    @Volatile
    var service: InCallService? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) { _revision.value++ }
        override fun onDetailsChanged(call: Call, details: Call.Details) { _revision.value++ }
    }

    fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        _call.value = call
        _revision.value++
    }

    fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (_call.value == call) _call.value = null
        _revision.value++
    }

    fun onMuteChanged(muted: Boolean) {
        _muted.value = muted
    }

    fun setMuted(muted: Boolean) {
        service?.setMuted(muted)
    }
}
