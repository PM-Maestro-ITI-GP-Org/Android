package com.motorguard.ivi.ui.web

import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.PlaybackSnapshot
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a web player is playing, reported by the page itself.
 *
 * There is no other way to know. Android System WebView — unlike Chrome the browser —
 * deliberately publishes no platform `MediaSession` for page media, so the trick that reads the
 * phone over Bluetooth finds nothing here: `dumpsys media_session` shows only our own idle
 * session while the WebView is audibly playing.
 *
 * What the page *does* expose is the Media Session API, and Spotify and YouTube both populate
 * it. [WebSession] injects a small script that watches `navigator.mediaSession.metadata` and
 * reports changes here, which is how the now-playing card learns a web source is playing at all.
 *
 * Metadata only. The page owns the transport, and the snapshot says so with
 * [PlaybackKind.WEB] rather than offering controls that would do nothing.
 */
object WebPlayback {

    private val _state = MutableStateFlow<PlaybackSnapshot?>(null)
    val state: StateFlow<PlaybackSnapshot?> = _state.asStateFlow()

    /** Called from the JavaScript bridge, off the main thread. */
    fun report(
        source: MediaSourceId,
        title: String,
        artist: String,
        album: String,
        playing: Boolean,
    ) {
        android.util.Log.d("MGWeb", "report $source '$title' / '$artist' playing=$playing")
        if (title.isBlank()) {
            clear(source)
            return
        }
        _state.value = PlaybackSnapshot(
            track = Track(
                // Keyed by what is playing, not by a page id: the artwork cache is keyed on this
                // and a constant would freeze the cover on the first track, exactly as the
                // Bluetooth mirror's fixed "currsong" id did.
                id = "web:${source.name}:$title|$artist|$album",
                title = title,
                artist = artist,
                album = album,
                durationMs = 0L,
                uri = null,
                artworkUri = null,
                source = source,
            ),
            isPlaying = playing,
            source = source,
            playbackKind = PlaybackKind.WEB,
            // The page owns both, so neither is generally safe to advertise — except YouTube
            // Music, whose player bar always keeps a real next/previous queue that
            // WebSession.skipNext/skipPrevious can reach by clicking the page's own buttons.
            canSeek = false,
            canSkip = source == MediaSourceId.YOUTUBE_MUSIC,
        )
    }

    /** Leaving the tab, or the page stopped. Only clears if this source is the one showing. */
    fun clear(source: MediaSourceId) {
        if (_state.value?.source == source) _state.value = null
    }
}
