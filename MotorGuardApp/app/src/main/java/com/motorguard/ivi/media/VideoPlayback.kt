package com.motorguard.ivi.media

import com.motorguard.ivi.data.media.PlaybackSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The video player's state, published to the rest of the app.
 *
 * Video is the one source whose audio does not come from the shared session — the pane owns a
 * local [androidx.media3.exoplayer.ExoPlayer] because a `MediaController` cannot be given a
 * surface. That is a necessary design, but it left [MediaConnection] with no idea a video was
 * playing, so the Home now-playing card sat blank (or showed a stale track) while a film was
 * audible. This is the way back: the pane pushes its snapshot here, and MediaConnection folds it
 * into the same precedence as everything else.
 *
 * Exactly the shape of the Bluetooth problem, and fixed the same way — anything that produces
 * audio outside the session has to report in, or the widget that claims to show "now playing"
 * is lying.
 */
object VideoPlayback {

    /** What the video pane is playing, or null when it is not composed / has no item. */
    private val _state = MutableStateFlow<PlaybackSnapshot?>(null)
    val state: StateFlow<PlaybackSnapshot?> = _state.asStateFlow()

    /**
     * Transport for the pane's player.
     *
     * Held as a callback rather than as the player itself so nothing outside the pane can retain
     * a released player — the pane's lifetime is the player's lifetime, deliberately.
     */
    interface Controls {
        fun playPause()
        fun seekTo(positionMs: Long)
    }

    private var controls: Controls? = null

    fun attach(controls: Controls) {
        this.controls = controls
    }

    /** Called when the pane leaves the screen and its player is released. */
    fun detach(controls: Controls) {
        if (this.controls === controls) {
            this.controls = null
            _state.value = null
        }
    }

    fun update(snapshot: PlaybackSnapshot?) {
        _state.value = snapshot
    }

    fun playPause() {
        controls?.playPause()
    }

    fun seekTo(positionMs: Long) {
        controls?.seekTo(positionMs)
    }
}
