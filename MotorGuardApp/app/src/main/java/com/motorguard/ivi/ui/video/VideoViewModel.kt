package com.motorguard.ivi.ui.video

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
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoUiState(
    val videos: List<Track> = emptyList(),
    val selected: Track? = null,
    val loading: Boolean = false,
    val isMoving: Boolean = false,
) {
    val summary: String
        get() = when {
            loading -> "Scanning…"
            videos.isEmpty() -> "Nothing found"
            videos.size == 1 -> "1 video"
            else -> "${videos.size} videos"
        }
}

/**
 * State for the Videos destination.
 *
 * Separate from `MediaViewModel` because video does not share the media session at all — see
 * [com.motorguard.ivi.data.media.PlaybackKind.VIDEO]. The list still comes from the same
 * [MediaSourceManager] source, so there is one MediaStore query behind both the old tab and this
 * screen.
 */
class VideoViewModel(application: Application) : AndroidViewModel(application) {

    private val sources = MediaSourceManager.get(application)

    private val _state = MutableStateFlow(VideoUiState())
    val state: StateFlow<VideoUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var rescanJob: Job? = null
    private var observer: ContentObserver? = null

    init {
        load()
        viewModelScope.launch {
            DrivingState.isMoving(getApplication()).collect { moving ->
                _state.update { it.copy(isMoving = moving) }
            }
        }
        observeMediaStore()
    }

    fun play(index: Int) {
        val video = _state.value.videos.getOrNull(index) ?: return
        _state.update { it.copy(selected = video) }
    }

    private fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val videos = runCatching { sources.tracks(MediaSourceId.VIDEO) }.getOrDefault(emptyList())
            _state.update { it.copy(videos = videos, loading = false) }
        }
    }

    /** Same reason as the audio library: a stick is mounted well before it is indexed. */
    private fun observeMediaStore() {
        val resolver = getApplication<Application>().contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                rescanJob?.cancel()
                rescanJob = viewModelScope.launch {
                    delay(SCAN_SETTLE_MS)
                    load()
                }
            }
        }
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                /* notifyForDescendants = */ true,
                obs,
            )
        }
        observer = obs
    }

    override fun onCleared() {
        observer?.let { o ->
            runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(o) }
        }
        super.onCleared()
    }

    private companion object {
        const val SCAN_SETTLE_MS = 1_200L
    }
}
