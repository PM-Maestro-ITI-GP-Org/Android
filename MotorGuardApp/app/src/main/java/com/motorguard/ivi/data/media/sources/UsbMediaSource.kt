package com.motorguard.ivi.data.media.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.motorguard.ivi.data.media.MediaLibrarySource
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

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

        // The ACTION_MEDIA_* broadcasts are not dependable on their own here: they are sent per
        // user, and on automotive the app runs as the driver user rather than the one vold
        // announces to. StorageManager's callback is delivered directly and is the reason a stick
        // plugged in after boot now registers at all.
        val storage = context.getSystemService(StorageManager::class.java)
        val volumeCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) = push()
            }.also {
                runCatching { storage?.registerStorageVolumeCallback(context.mainExecutor, it) }
            }
        } else {
            null
        }

        // A drive present at launch produces no event at all, so the tab has to ask once itself.
        push()

        // Mounting and indexing are not the same instant: MediaStore may still be scanning when
        // the volume appears, and the track query would come back empty. A couple of re-asks cost
        // nothing and save the driver from having to leave the tab and come back.
        val settle = launch {
            repeat(SETTLE_POLLS) {
                delay(SETTLE_INTERVAL_MS)
                push()
            }
        }

        awaitClose {
            settle.cancel()
            runCatching { context.unregisterReceiver(receiver) }
            if (volumeCallback != null) {
                runCatching { storage?.unregisterStorageVolumeCallback(volumeCallback) }
            }
        }
    }

    override suspend fun tracks(): List<Track> = MediaStoreQuery.tracks(
        context = context,
        volumes = MediaStoreQuery.removableVolumeNames(context),
        source = id,
    )

    private companion object {
        /** Roughly 15 s of cover, which is longer than a stick of any normal size takes to scan. */
        const val SETTLE_POLLS = 5
        const val SETTLE_INTERVAL_MS = 3_000L
    }
}
