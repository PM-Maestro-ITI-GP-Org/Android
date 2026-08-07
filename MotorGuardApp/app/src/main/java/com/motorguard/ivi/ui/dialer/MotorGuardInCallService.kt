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

    override fun onCallAdded(call: Call) {
        Log.i(TAG, "call added")
        InCallBridge.service = this
        InCallBridge.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        Log.i(TAG, "call removed")
        InCallBridge.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        InCallBridge.onMuteChanged(audioState.isMuted)
    }

    override fun onDestroy() {
        if (InCallBridge.service === this) InCallBridge.service = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MotorGuardPhone"
    }
}
