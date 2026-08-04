package com.motorguard.ivi.data.media.sources

import android.content.Context
import android.provider.MediaStore
import com.motorguard.ivi.data.media.MediaLibrarySource
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Music on the head unit's own storage. The source that always works — no stick, no phone, no
 * tuner — which makes it the one the whole feature can be demoed and tested against.
 */
class LocalMediaSource(private val context: Context) : MediaLibrarySource {

    override val id = MediaSourceId.LOCAL
    override val label = "Library"
    override val playbackKind = PlaybackKind.LOCAL_PLAYER

    override fun availability(): Flow<SourceAvailability> = flow {
        emit(
            SourceAvailability(
                id = id,
                available = MediaStoreQuery.hasPermission(context),
                emptyMessage = "Allow access to media to browse the library",
            ),
        )
    }

    override suspend fun tracks(): List<Track> = MediaStoreQuery.tracks(
        context = context,
        volumes = setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        source = id,
    )
}
