package com.motorguard.ivi.ui.media

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.MediaSourceManager
import com.motorguard.ivi.data.media.PlaybackSnapshot
import com.motorguard.ivi.data.media.QueueSummary
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import com.motorguard.ivi.media.MediaConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * State for the Media tab.
 *
 * Playback state comes from [MediaConnection] (one session, shared with the Home widget); the
 * browsable track list comes from [MediaSourceManager]. Keeping those separate is what lets the
 * queue card show durations and track numbers that a `MediaItem` round-trip would have dropped.
 */
class MediaViewModel(application: Application) : AndroidViewModel(application) {

    private val connection = MediaConnection.get(application)
    private val sources = MediaSourceManager.get(application)

    private val _state = MutableStateFlow(MediaUiState())
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            connection.state.collect { playback -> _state.update { it.copy(playback = playback) } }
        }
        viewModelScope.launch {
            sources.availability().collect { availability ->
                _state.update { it.copy(availability = availability) }
            }
        }
        viewModelScope.launch {
            sources.active.collect { id ->
                _state.update { it.copy(activeSource = id) }
                loadTracks(id)
            }
        }
    }

    /**
     * Re-read the library — after a permission grant, or a USB stick appearing.
     *
     * Availability is re-evaluated too, not just the tracks: the screen shows a source's
     * empty message *instead of* its list while it reads as unavailable, so re-loading the
     * tracks alone would leave the driver staring at "Allow access to media…" over a library
     * that had in fact just loaded.
     */
    fun refresh() {
        sources.refreshAvailability()
        loadTracks(_state.value.activeSource)
    }

    private fun loadTracks(id: MediaSourceId) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val tracks = runCatching { sources.tracks(id) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    tracks = tracks,
                    loading = false,
                    queue = summarize(sources.source(id).label, tracks),
                )
            }
        }
    }

    fun selectSource(id: MediaSourceId) = connection.setSource(id)

    /** Play the queue from [index] — the whole visible list becomes the queue, as in the design. */
    fun playTrack(index: Int) = connection.play(_state.value.tracks, index)

    fun playPause() = connection.playPause()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
    fun toggleShuffle() = connection.toggleShuffle()
    fun cycleRepeat() = connection.cycleRepeat()

    /**
     * "Today's Top Hits · 38 songs · 2h 21m". Names the album when the whole list is one, which
     * is the common case for a USB stick holding a single record.
     */
    private fun summarize(sourceLabel: String, tracks: List<Track>): QueueSummary {
        if (tracks.isEmpty()) return QueueSummary(sourceLabel, "No tracks")

        val albums = tracks.map { it.album }.filter { it.isNotBlank() }.distinct()
        val title = if (albums.size == 1) albums.first() else sourceLabel

        val totalMs = tracks.sumOf { it.durationMs }
        val hours = TimeUnit.MILLISECONDS.toHours(totalMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMs) % 60
        val length = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        val songs = if (tracks.size == 1) "1 song" else "${tracks.size} songs"

        return QueueSummary(title, "$songs · $length")
    }
}

data class MediaUiState(
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val tracks: List<Track> = emptyList(),
    val availability: List<SourceAvailability> = emptyList(),
    val activeSource: MediaSourceId = MediaSourceId.LOCAL,
    val loading: Boolean = false,
    val queue: QueueSummary = QueueSummary("Library", ""),
) {
    fun availabilityOf(id: MediaSourceId): SourceAvailability? = availability.firstOrNull { it.id == id }
}
