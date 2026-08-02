package com.motorguard.ivi.data.media.sources

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared MediaStore reader. The on-device library and a USB stick are the same query against
 * different **storage volumes** — `external_primary` for one, whatever removable volumes are
 * mounted for the other — so the difference is a parameter, not a second implementation.
 */
internal object MediaStoreQuery {

    /** Read permission split at API 33, when the storage permissions were broken up by type. */
    val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    /** Every mounted volume MediaStore knows about, including removable ones. */
    fun volumeNames(context: Context): Set<String> =
        runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())

    /** Volumes that are not the built-in storage — i.e. USB sticks and SD cards. */
    fun removableVolumeNames(context: Context): Set<String> =
        volumeNames(context) - MediaStore.VOLUME_EXTERNAL_PRIMARY

    /**
     * Query audio on [volumes]. Returns empty on a missing permission or an unreadable volume
     * rather than throwing: a stick pulled mid-query is routine, not exceptional.
     */
    suspend fun tracks(
        context: Context,
        volumes: Set<String>,
        source: MediaSourceId,
    ): List<Track> = withContext(Dispatchers.IO) {
        if (!hasPermission(context) || volumes.isEmpty()) return@withContext emptyList()

        volumes.flatMap { volume ->
            runCatching { queryVolume(context, volume, source) }.getOrDefault(emptyList())
        }.sortedWith(compareBy({ it.album }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title }))
    }

    private fun queryVolume(context: Context, volume: String, source: MediaSourceId): List<Track> {
        val collection = MediaStore.Audio.Media.getContentUri(volume)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.ALBUM_ID,
        )

        // IS_MUSIC filters out ringtones, notifications and podcasts, which are all in here too.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} > ?"
        val args = arrayOf(MIN_DURATION_MS.toString())

        val out = ArrayList<Track>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                // MediaStore's TRACK encodes disc number as the thousands digit (1005 = disc 1,
                // track 5), which sorts very strangely if taken at face value.
                val rawTrack = cursor.getInt(trackCol)
                out.add(
                    Track(
                        id = "$volume:$id",
                        title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                        artist = cursor.getString(artistCol)?.unknownToBlank().orEmpty(),
                        album = cursor.getString(albumCol)?.unknownToBlank().orEmpty(),
                        durationMs = cursor.getLong(durationCol),
                        uri = ContentUris.withAppendedId(collection, id),
                        artworkUri = albumArtUri(cursor.getLong(albumIdCol)),
                        source = source,
                        trackNumber = (rawTrack % 1000).takeIf { it > 0 },
                    ),
                )
            }
        }
        return out
    }

    /**
     * Album art collection URI. Deprecated since API 29 in favour of `loadThumbnail`, but still
     * the only form that can be handed to Media3 as a `MediaMetadata.artworkUri` for the
     * notification to resolve later. [com.motorguard.ivi.data.media.AlbumArtLoader] prefers
     * `loadThumbnail` and falls back to this.
     */
    private fun albumArtUri(albumId: Long): Uri? =
        if (albumId <= 0) null else ContentUris.withAppendedId(ALBUM_ART_COLLECTION, albumId)

    private val ALBUM_ART_COLLECTION: Uri = Uri.parse("content://media/external/audio/albumart")

    /** MediaStore writes the literal string "<unknown>" for missing tags. */
    private fun String.unknownToBlank(): String = if (this == "<unknown>") "" else this

    /** Skip sub-second files — almost always UI sounds rather than music. */
    private const val MIN_DURATION_MS = 5_000
}
