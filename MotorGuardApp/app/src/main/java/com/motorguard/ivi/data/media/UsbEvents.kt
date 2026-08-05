package com.motorguard.ivi.data.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Removable storage appearing and disappearing, as events rather than as state.
 *
 * [UsbMediaSource][com.motorguard.ivi.data.media.sources.UsbMediaSource] already answers "is a
 * stick present", but a level cannot say *"a stick was just plugged in"* — and that is the thing
 * worth telling the driver about. Plugging a drive into a head unit and having the screen not
 * acknowledge it at all reads as a broken port.
 *
 * Mount is not the end of the story: vold mounts a drive well before MediaProvider has finished
 * indexing it, so the tracks are not queryable at the moment this fires. Whoever handles
 * [Event.Mounted] has to keep looking — see the MediaStore observer in
 * [com.motorguard.ivi.ui.media.MediaViewModel].
 */
object UsbEvents {

    sealed interface Event {
        /** [label] is the drive's own name when it has one, for the banner text. */
        data class Mounted(val label: String?) : Event

        data object Removed : Event
    }

    fun stream(context: Context): Flow<Event> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_MEDIA_MOUNTED -> trySend(Event.Mounted(currentLabel(context)))
                    Intent.ACTION_MEDIA_UNMOUNTED,
                    Intent.ACTION_MEDIA_EJECT,
                    Intent.ACTION_MEDIA_REMOVED,
                    Intent.ACTION_MEDIA_BAD_REMOVAL,
                    -> trySend(Event.Removed)
                }
            }
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
        context.applicationContext.registerReceiver(receiver, filter)

        awaitClose {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
        }
    }

    /**
     * The mounted drive's own label, when it has one.
     *
     * Read from StorageManager rather than from the broadcast: the intent carries the mount path,
     * which is a number on AAOS and means nothing to a driver.
     */
    private fun currentLabel(context: Context): String? = runCatching {
        context.getSystemService(StorageManager::class.java)
            ?.storageVolumes
            ?.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            ?.getDescription(context)
    }.getOrNull()
}
