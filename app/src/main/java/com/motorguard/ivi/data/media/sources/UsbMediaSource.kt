package com.motorguard.ivi.data.media.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.motorguard.ivi.data.media.MediaLibrarySource
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A USB stick, which to MediaStore is simply a non-primary storage volume.
 *
 * Availability is event-driven rather than polled: the platform broadcasts mount and eject, and
 * the tab flips the moment the driver plugs in or pulls the drive. Note that the *eject*
 * broadcast is the one that matters for correctness — a stick pulled without unmounting leaves
 * a queue full of URIs that no longer resolve, and the tab needs to say so rather than fail
 * track by track.
 */
class UsbMediaSource(private val context: Context) : MediaLibrarySource {

    override val id = MediaSourceId.USB
    override val label = "USB"
    override val playbackKind = PlaybackKind.LOCAL_PLAYER

    override fun availability(): Flow<SourceAvailability> = callbackFlow {
        fun push() {
            trySend(
                SourceAvailability(
                    id = id,
                    available = MediaStoreQuery.removableVolumeNames(context).isNotEmpty(),
                    emptyMessage = "Insert USB drive",
                ),
            )
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = push()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            // These broadcasts carry a file:// URI, so the filter needs the scheme or it will
            // silently never match.
            addDataScheme("file")
        }
        context.registerReceiver(receiver, filter)

        push()
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    override suspend fun tracks(): List<Track> = MediaStoreQuery.tracks(
        context = context,
        volumes = MediaStoreQuery.removableVolumeNames(context),
        source = id,
    )
}
