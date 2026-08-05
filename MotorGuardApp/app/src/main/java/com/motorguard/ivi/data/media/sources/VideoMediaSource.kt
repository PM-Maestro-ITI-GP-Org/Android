package com.motorguard.ivi.data.media.sources

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.motorguard.ivi.data.media.MediaLibrarySource
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Video files, from the head unit's own storage and from any mounted stick.
 *
 * Deliberately not split into two tabs the way audio is. A driver looking for a film does not
 * care which volume it is on, and there are rarely enough video files for the distinction to
 * earn a tab of its own — so both volumes are queried and the results merged.
 *
 * [PlaybackKind.VIDEO] means a local player owns this, not the background service; see that
 * constant for why the shared session cannot render video.
 */
class VideoMediaSource(private val context: Context) : MediaLibrarySource {

    override val id = MediaSourceId.VIDEO
    override val label = "Videos"
    override val playbackKind = PlaybackKind.VIDEO

    override fun availability(): Flow<SourceAvailability> = flow {
        emit(
            SourceAvailability(
                id = id,
                available = MediaStoreQuery.hasPermission(context),
                emptyMessage = "Allow access to media to browse videos",
            ),
        )
    }

    override suspend fun tracks(): List<Track> = withContext(Dispatchers.IO) {
        if (!MediaStoreQuery.hasPermission(context)) return@withContext emptyList()

        val volumes = setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) +
            MediaStoreQuery.removableVolumeNames(context)

        volumes.flatMap { volume ->
            runCatching { queryVolume(volume) }.getOrDefault(emptyList())
        }.sortedBy { it.title.lowercase() }
    }

    private fun queryVolume(volume: String): List<Track> {
        val collection = MediaStore.Video.Media.getContentUri(volume)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
        )

        // Same reasoning as the audio query: skip the sub-second files, which on a device that
        // has ever run a camera app are thumbnails and UI captures rather than anything watchable.
        val selection = "${MediaStore.Video.Media.DURATION} > ?"
        val args = arrayOf(MIN_DURATION_MS.toString())

        val out = ArrayList<Track>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                // TITLE is derived from the tags and is often blank or a meaningless container
                // name; the filename is what the driver actually recognises.
                val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(nameCol)?.substringBeforeLast('.')
                    ?: "Untitled"

                out.add(
                    Track(
                        id = "video:$volume:$id",
                        title = title,
                        // The queue row's second line. A video has no artist, and its size is the
                        // one fact worth knowing before opening it off a slow stick.
                        artist = cursor.getLong(sizeCol).asFileSize(),
                        album = "",
                        durationMs = cursor.getLong(durationCol),
                        uri = ContentUris.withAppendedId(collection, id),
                        // No thumbnail URI: video thumbnails need loadThumbnail(), which is a
                        // blocking call per item and would stall the list. The pane shows the
                        // first frame once the player is attached instead.
                        artworkUri = null,
                        source = id(),
                    ),
                )
            }
        }
        return out
    }

    private fun id() = id

    private fun Long.asFileSize(): String = when {
        this <= 0L -> ""
        this >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1f GB", this / 1e9)
        else -> "${this / 1_000_000} MB"
    }

    private companion object {
        const val MIN_DURATION_MS = 1_000
    }
}
