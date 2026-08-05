package com.motorguard.ivi.data.media.sources

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.motorguard.ivi.data.media.MediaLibrarySource
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The driver's phone, over A2DP (audio) and AVRCP (metadata + transport).
 *
 * This source is [PlaybackKind.EXTERNAL_SESSION]: the phone owns the stream and the queue, and
 * the head unit is a remote control with a screen. That is why [tracks] is always empty — there
 * is no library to browse here. AVRCP 1.4 does define browsing, but Android exposes no public
 * API for it, so what a real car shows on this tab is the *current* track and nothing more.
 * The now-playing data comes from
 * [com.motorguard.ivi.media.BluetoothSessionMirror], not from this list.
 *
 * **Not yet verified against a phone.** The profile role below was wrong (source rather than
 * sink) and is now corrected, but confirming it takes a handset actually paired to the unit.
 */
class BluetoothMediaSource(private val context: Context) : MediaLibrarySource {

    override val id = MediaSourceId.BLUETOOTH
    override val label = "Bluetooth"
    override val playbackKind = PlaybackKind.EXTERNAL_SESSION

    override fun availability(): Flow<SourceAvailability> = callbackFlow {
        fun push() {
            trySend(
                SourceAvailability(
                    id = id,
                    available = isA2dpConnected(),
                    emptyMessage = if (hasBluetoothPermission()) {
                        "Connect a phone"
                    } else {
                        "Allow Bluetooth access in Settings"
                    },
                ),
            )
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = push()
        }
        // The sink profile's own state-change action is @hide, so the ACL broadcasts stand in for
        // it: they are public, they fire for the same events, and a redundant re-read of one
        // integer costs nothing.
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)

        push()
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    /** Always empty: AVRCP gives us a current track, not a browsable library. See the class KDoc. */
    override suspend fun tracks(): List<Track> = emptyList()

    /**
     * Is a phone streaming to us?
     *
     * `A2DP` is the *source* role — what a phone plays. A head unit is the **sink**, so asking
     * about A2DP here was asking whether the car was streaming out to something, which it never
     * is, and the tab was therefore permanently unavailable. `A2DP_SINK` is the role this
     * hardware actually holds; the constant is `@hide`, hence the literal.
     */
    private fun isA2dpConnected(): Boolean {
        if (!hasBluetoothPermission()) return false
        val adapter = context.getSystemService<BluetoothManager>()?.adapter ?: return false
        // getProfileConnectionState is the cheap synchronous answer; the alternative is holding a
        // BluetoothProfile proxy open for the lifetime of the app just to read one integer.
        return runCatching {
            adapter.getProfileConnectionState(PROFILE_A2DP_SINK) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }

    /** `BLUETOOTH_CONNECT` became a runtime permission in API 31; before that it was implicit. */
    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** `BluetoothProfile.A2DP_SINK`, which is `@hide`. Stable since it was introduced. */
        const val PROFILE_A2DP_SINK = 11
    }
}
