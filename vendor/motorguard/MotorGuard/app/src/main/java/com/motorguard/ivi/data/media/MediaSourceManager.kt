package com.motorguard.ivi.data.media

import android.content.Context
import com.motorguard.ivi.data.media.sources.BluetoothMediaSource
import com.motorguard.ivi.data.media.sources.LocalMediaSource
import com.motorguard.ivi.data.media.sources.RadioMediaSource
import com.motorguard.ivi.data.media.sources.UsbMediaSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Owns the four sources and which one is active.
 *
 * A process singleton because the playback service and the UI both need the same answer to
 * "what is the USB stick showing right now", and two independent scans would be both wasteful
 * and capable of disagreeing.
 *
 * Switching source stops the previous one — docs/04-media.md's "single AudioFocus owner" rule.
 * That is enforced in the service (which holds the player), not here; this class only records
 * the choice.
 */
class MediaSourceManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    val sources: List<MediaLibrarySource> = listOf(
        LocalMediaSource(appContext),
        UsbMediaSource(appContext),
        BluetoothMediaSource(appContext),
        RadioMediaSource(),
    )

    private val _active = MutableStateFlow(MediaSourceId.LOCAL)
    val active: StateFlow<MediaSourceId> = _active.asStateFlow()

    fun source(id: MediaSourceId): MediaLibrarySource =
        sources.first { it.id == id }

    fun setActive(id: MediaSourceId) {
        _active.value = id
    }

    /**
     * Bumped when something changed an availability answer that the sources cannot observe for
     * themselves. Today that is the runtime media permission: [LocalMediaSource] reads it once
     * when collected and Android broadcasts nothing on a grant, so without this nudge the
     * library would stay "Allow access to media…" until the fragment was recreated.
     */
    private val _revision = MutableStateFlow(0)

    /** Re-evaluate every source's availability. Call after a runtime permission grant. */
    fun refreshAvailability() {
        _revision.value++
    }

    /** Availability of every source at once, in tab order, for the source switcher. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun availability(): Flow<List<SourceAvailability>> =
        _revision.flatMapLatest {
            combine(sources.map { it.availability() }) { states -> states.toList() }
        }

    suspend fun tracks(id: MediaSourceId): List<Track> = source(id).tracks()

    companion object {
        @Volatile
        private var instance: MediaSourceManager? = null

        fun get(context: Context): MediaSourceManager =
            instance ?: synchronized(this) {
                instance ?: MediaSourceManager(context).also { instance = it }
            }
    }
}
