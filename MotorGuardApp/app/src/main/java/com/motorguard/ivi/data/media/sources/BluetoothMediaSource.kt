package com.motorguard.ivi.data.media.sources

import android.Manifest
import android.bluetooth.BluetoothA2dp
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
 * The now-playing data comes from [BluetoothSessionMirror], not from this list.
 *
 * **Untested.** There was no paired phone available while this was written; the availability
 * logic below is the conventional A2DP profile check and should be verified on hardware.
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
        val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        context.registerReceiver(receiver, filter)

        push()
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    /** Always empty: AVRCP gives us a current track, not a browsable library. See the class KDoc. */
    override suspend fun tracks(): List<Track> = emptyList()

    private fun isA2dpConnected(): Boolean {
        if (!hasBluetoothPermission()) return false
        val adapter = context.getSystemService<BluetoothManager>()?.adapter ?: return false
        // getProfileConnectionState is the cheap synchronous answer; the alternative is holding a
        // BluetoothProfile proxy open for the lifetime of the app just to read one integer.
        return runCatching {
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }

    /** `BLUETOOTH_CONNECT` became a runtime permission in API 31; before that it was implicit. */
    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
