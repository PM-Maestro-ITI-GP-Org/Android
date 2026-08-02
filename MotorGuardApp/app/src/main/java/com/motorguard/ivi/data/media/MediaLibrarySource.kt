package com.motorguard.ivi.data.media

import kotlinx.coroutines.flow.Flow

/**
 * One tab's worth of media: what it is called, whether it can be used, and what is in it.
 *
 * Implementations live in `data/media/sources`. Adding a source — a streaming service, a
 * second USB port — means adding one file and one entry in [MediaSourceManager], with no change
 * to the service or the UI.
 */
interface MediaLibrarySource {

    val id: MediaSourceId

    /** Tab label. */
    val label: String

    /** How audio is produced; see [PlaybackKind] for why this is not uniform. */
    val playbackKind: PlaybackKind

    /**
     * Hot flow of usability. Cold-start emits immediately so a tab never renders blank while
     * waiting to learn whether a USB stick is plugged in.
     */
    fun availability(): Flow<SourceAvailability>

    /**
     * The source's content. Returns empty rather than throwing when unavailable — an unplugged
     * drive is a normal state, not an error, and the tab shows [SourceAvailability.emptyMessage].
     */
    suspend fun tracks(): List<Track>
}
