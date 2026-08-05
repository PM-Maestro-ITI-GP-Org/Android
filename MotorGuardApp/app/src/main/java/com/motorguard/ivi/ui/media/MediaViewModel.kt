package com.motorguard.ivi.ui.media

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motorguard.ivi.data.media.DrivingState
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.MediaSourceManager
import com.motorguard.ivi.data.media.PlaybackSnapshot
import com.motorguard.ivi.data.media.QueueSummary
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import com.motorguard.ivi.data.media.UsbEvents
import com.motorguard.ivi.data.media.sources.RadioMediaSource
import com.motorguard.ivi.media.MediaConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var rescanJob: Job? = null
    private var searchJob: Job? = null
    private var mediaStoreObserver: ContentObserver? = null

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
                // A search is scoped to the Radio tab; carrying the term across to the library
                // would leave a filter applied that the driver can no longer see.
                searchJob?.cancel()
                _state.update { it.copy(activeSource = id, searchQuery = "") }
                loadTracks(id)
            }
        }
        viewModelScope.launch {
            UsbEvents.stream(getApplication()).collect { event -> onUsbEvent(event) }
        }
        viewModelScope.launch {
            DrivingState.isMoving(getApplication()).collect { moving ->
                _state.update { it.copy(isMoving = moving) }
            }
        }
        observeMediaStore()
    }

    /**
     * Select a video to play.
     *
     * Separate from [playTrack] because video does not run through the shared session at all —
     * see [com.motorguard.ivi.data.media.PlaybackKind.VIDEO]. The chosen item is held in state
     * and the pane's own player opens it.
     */
    fun playVideo(index: Int) {
        val track = _state.value.tracks.getOrNull(index) ?: return
        _state.update { it.copy(selectedVideo = track) }
    }

    /**
     * Tell the driver a drive appeared, and go and find what is on it.
     *
     * The source is deliberately *not* switched: the driver may be mid-track on something else,
     * and a head unit that changes what is playing because a passenger charged their phone is a
     * worse bug than the one being fixed. The banner offers the switch instead.
     */
    private fun onUsbEvent(event: UsbEvents.Event) {
        when (event) {
            is UsbEvents.Event.Mounted -> {
                val name = event.label?.takeIf { it.isNotBlank() } ?: "USB drive"
                _state.update { it.copy(notice = MediaNotice.UsbMounted(name)) }
                sources.refreshAvailability()
                refresh()
            }

            UsbEvents.Event.Removed -> {
                sources.refreshAvailability()
                _state.update {
                    // A queue of URIs on a drive that is gone resolves to nothing, so it is
                    // cleared rather than left to fail track by track.
                    val cleared = if (it.activeSource == MediaSourceId.USB) emptyList() else it.tracks
                    it.copy(notice = MediaNotice.UsbRemoved, tracks = cleared)
                }
                refresh()
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Search the station directory.
     *
     * Debounced rather than fired per keystroke: this is a network call per character otherwise,
     * against a volunteer-run directory, and the results would race each other back out of order.
     * The field is updated immediately so typing stays responsive; only the query is delayed.
     */
    fun searchStations(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val source = sources.source(MediaSourceId.RADIO) as? RadioMediaSource ?: return@launch
            _state.update { it.copy(loading = true) }
            val results = runCatching { source.search(query) }.getOrDefault(emptyList())
            _state.update {
                // A result that arrives after the driver has moved to another tab must not
                // replace that tab's list.
                if (it.activeSource != MediaSourceId.RADIO) it
                else it.copy(
                    tracks = results,
                    loading = false,
                    queue = QueueSummary(
                        if (query.isBlank()) "Radio" else "“$query”",
                        if (results.isEmpty()) "No stations" else "${results.size} stations",
                    ),
                )
            }
        }
    }

    /**
     * Re-read the list whenever MediaStore's audio table changes.
     *
     * This is what makes a freshly plugged stick actually show its music. vold mounts the drive
     * long before MediaProvider has indexed it, so the query fired on the mount broadcast lands
     * on an empty table and the tab sits there saying nothing — which is the bug as the driver
     * experiences it. Indexing then completes and notifies here, and the list fills in. It also
     * covers files copied onto the head unit while the app is open.
     */
    private fun observeMediaStore() {
        val resolver = getApplication<Application>().contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // Indexing a drive emits a burst of notifications, one per file; re-querying on
                // each would be dozens of scans of the same volume.
                rescanJob?.cancel()
                rescanJob = viewModelScope.launch {
                    delay(SCAN_SETTLE_MS)
                    loadTracks(_state.value.activeSource)
                }
            }
        }
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                /* notifyForDescendants = */ true,
                observer,
            )
        }
        mediaStoreObserver = observer
    }

    override fun onCleared() {
        mediaStoreObserver?.let { observer ->
            runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(observer) }
        }
        super.onCleared()
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

    private companion object {
        /** Long enough to let a burst of indexing notifications finish arriving. */
        const val SCAN_SETTLE_MS = 1_200L

        /** Long enough to stop mid-word, short enough to feel like live search. */
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}

data class MediaUiState(
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val tracks: List<Track> = emptyList(),
    val availability: List<SourceAvailability> = emptyList(),
    val activeSource: MediaSourceId = MediaSourceId.LOCAL,
    val loading: Boolean = false,
    val queue: QueueSummary = QueueSummary("Library", ""),
    /** Transient banner — a drive appearing or going away. Null when there is nothing to say. */
    val notice: MediaNotice? = null,
    /** What is typed in the Radio tab's station search. */
    val searchQuery: String = "",
    /** The video the Videos tab is playing. Held here, not in the shared session. */
    val selectedVideo: Track? = null,
    /** True while the car is above walking pace — video is blocked. */
    val isMoving: Boolean = false,
) {
    fun availabilityOf(id: MediaSourceId): SourceAvailability? = availability.firstOrNull { it.id == id }
}

/** Something worth interrupting the driver with, briefly. */
sealed interface MediaNotice {
    data class UsbMounted(val label: String) : MediaNotice
    data object UsbRemoved : MediaNotice
}
