package com.motorguard.ivi.ui.dialer

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.motorguard.ivi.data.InCallBridge

/**
 * Bound by the platform when Motor Guard holds the DIALER role. It owns no UI and no
 * logic — it forwards the live call and the audio state into [InCallBridge], which the
 * repository projects into domain state. That keeps the "only the data layer touches
 * Telecom" rule intact even though the platform, not us, constructs this object.
 *
 * Grant the role on a userdebug image:
 *   adb shell cmd role add-role-holder android.app.role.DIALER com.motorguard.ivi
 *
 * Without the role the service is never bound and calls placed through Telecom will be
 * controlled by whatever dialer does hold it.
 */
class MotorGuardInCallService : InCallService() {

    // Each of these is the platform calling into the launcher process. An exception thrown
    // back at Telecom from here is an uncaught exception in *this* app, so a call that cannot
    // be taken up must degrade to no in-call screen rather than to no launcher.
    override fun onCallAdded(call: Call) {
        Log.i(TAG, "call added")
        runCatching {
            InCallBridge.service = this
            InCallBridge.onCallAdded(call)
        }.onFailure { Log.e(TAG, "call not added", it) }
    }

    override fun onCallRemoved(call: Call) {
        Log.i(TAG, "call removed")
        runCatching { InCallBridge.onCallRemoved(call) }
            .onFailure { Log.e(TAG, "call not removed", it) }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        runCatching { InCallBridge.onMuteChanged(audioState.isMuted) }
            .onFailure { Log.w(TAG, "audio state not applied", it) }
    }

    override fun onDestroy() {
        if (InCallBridge.service === this) InCallBridge.service = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MotorGuardPhone"
    }
}
