package com.motorguard.ivi.media

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.PlaybackSnapshot
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * The phone's playback, mirrored onto our UI.
 *
 * The Bluetooth tab was empty because there was nothing behind it: [PlaybackKind.EXTERNAL_SESSION]
 * says the phone owns the audio and the queue, but no code ever went and read that state, so the
 * tab rendered whatever our own idle ExoPlayer happened to contain — which is nothing.
 *
 * What fills the gap is already on the device. With `AvrcpControllerService` enabled the
 * Bluetooth stack publishes the connected phone's metadata and transport as an ordinary
 * `MediaBrowserService` ([SERVICE_PACKAGE]/[SERVICE_CLASS]) — that service is precisely how AAOS
 * expects a car's media UI to show a phone. Attaching a media3 [MediaBrowser] to it turns the
 * phone into a [Player] we can read with the same code that reads our own, so the now-playing
 * card does not need to know which source it is looking at.
 *
 * Two things this deliberately does **not** do. It never opens an audio stream: A2DP audio goes
 * phone → car speakers in the stack, below this app, and touching it here would be fighting the
 * platform. And it does not browse: AVRCP 1.4 defines a browse tree but Android exposes no
 * public API for it, so what a real car shows on this tab is the current track and nothing more.
 */
@OptIn(UnstableApi::class)
class BluetoothSessionMirror private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var browser: MediaBrowser? = null
    private var positionTicker: Job? = null
    private var connectJob: Job? = null

    private val _state = MutableStateFlow(EMPTY)
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    /** True once the stack's session has accepted us — i.e. there is a phone to mirror. */
    val isAttached: Boolean get() = browser != null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    init {
        // A phone connecting is the moment there is something to mirror. Without this the only
        // attempt is the bounded retry below, which has long since given up by the time the
        // driver pairs their phone — and the now-playing card then stays blank for the rest of
        // the session while music is audibly playing.
        runCatching {
            appContext.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        when (intent?.action) {
                            BluetoothDevice.ACTION_ACL_CONNECTED -> connect()
                            // The stack's session outlives the peer but reports nothing useful,
                            // so the attachment is dropped rather than left showing a dead track.
                            BluetoothDevice.ACTION_ACL_DISCONNECTED -> release()
                        }
                    }
                },
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
            )
        }
        connect()
    }

    /**
     * Attach to the Bluetooth stack's session, retrying while there is no phone.
     *
     * The service exists whether or not anything is connected, but it refuses the connection
     * until AVRCP has a peer — so a single attempt at startup would leave the tab permanently
     * dead for the ordinary case of the driver pairing their phone *after* the car booted. The
     * retry is slow and bounded rather than a tight poll: this runs on the main thread and the
     * driver is not watching a stopwatch.
     */
    fun connect() {
        if (browser != null || connectJob?.isActive == true) return
        connectJob = scope.launch {
            repeat(MAX_ATTEMPTS) {
                val token = SessionToken(
                    appContext,
                    ComponentName(SERVICE_PACKAGE, SERVICE_CLASS),
                )
                val attached = runCatching {
                    MediaBrowser.Builder(appContext, token).buildAsync().await()
                }.getOrNull()

                if (attached != null) {
                    browser = attached
                    attached.addListener(listener)
                    publish()
                    return@launch
                }
                delay(RETRY_MS)
            }
        }
    }

    /** Drop the attachment — the phone went away, or the Bluetooth tab was left. */
    fun release() {
        connectJob?.cancel()
        positionTicker?.cancel()
        browser?.removeListener(listener)
        browser?.release()
        browser = null
        _state.value = EMPTY
    }

    // ---------------------------------------------------------------- transport

    /**
     * Transport commands travel back over AVRCP to the phone, which is what makes them work at
     * all — we are not driving a local player, we are pressing buttons on someone else's.
     */
    fun playPause() = withBrowser { if (isPlaying) pause() else play() }

    fun next() = withBrowser { seekToNext() }

    fun previous() = withBrowser {
        if (currentPosition > RESTART_THRESHOLD_MS) seekTo(0) else seekToPrevious()
    }

    /** Many phones expose no AVRCP position control, so this is a request, not a guarantee. */
    fun seekTo(positionMs: Long) = withBrowser { seekTo(positionMs) }

    private inline fun withBrowser(block: MediaBrowser.() -> Unit) {
        runCatching { browser?.let(block) }
    }

    // ---------------------------------------------------------------- state

    private fun publish() {
        val active = browser
        if (active == null) {
            _state.value = EMPTY
            return
        }

        _state.value = PlaybackSnapshot(
            track = active.currentMediaItem?.toTrack(active.duration),
            isPlaying = active.isPlaying,
            positionMs = active.currentPosition.coerceAtLeast(0L),
            durationMs = active.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            source = MediaSourceId.BLUETOOTH,
            playbackKind = PlaybackKind.EXTERNAL_SESSION,
            // Asked of the session rather than assumed: AVRCP 1.3 peers support neither, 1.6
            // peers usually support both, and offering a scrubber that silently does nothing is
            // worse than not offering one.
            canSeek = active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            canSkip = active.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT),
        )

        positionTicker?.cancel()
        if (active.isPlaying) {
            positionTicker = scope.launch {
                while (true) {
                    delay(POSITION_TICK_MS)
                    val live = browser ?: break
                    _state.value = _state.value.copy(
                        positionMs = live.currentPosition.coerceAtLeast(0L),
                    )
                }
            }
        }
    }

    /**
     * The phone's track. [Track.uri] stays null on purpose — this item is describable but not
     * openable, and handing out a URI that would fail at play time is exactly what the null is
     * there to prevent.
     */
    private fun MediaItem.toTrack(durationMs: Long) = Track(
        id = mediaId.ifBlank { "bt-current" },
        title = mediaMetadata.title?.toString().orEmpty().ifBlank { "Unknown title" },
        artist = mediaMetadata.artist?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        durationMs = durationMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
        uri = null,
        artworkUri = mediaMetadata.artworkUri,
        source = MediaSourceId.BLUETOOTH,
        trackNumber = mediaMetadata.trackNumber,
    )

    companion object {
        /**
         * AOSP's AVRCP controller. Stable across releases — AAOS car media apps target this same
         * component to show the phone as a source.
         */
        private const val SERVICE_PACKAGE = "com.android.bluetooth"
        private const val SERVICE_CLASS =
            "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService"

        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val POSITION_TICK_MS = 500L
        private const val RETRY_MS = 4_000L
        private const val MAX_ATTEMPTS = 15

        private val EMPTY = PlaybackSnapshot(
            source = MediaSourceId.BLUETOOTH,
            playbackKind = PlaybackKind.EXTERNAL_SESSION,
            canSeek = false,
        )

        @Volatile
        private var instance: BluetoothSessionMirror? = null

        fun get(context: Context): BluetoothSessionMirror =
            instance ?: synchronized(this) {
                instance ?: BluetoothSessionMirror(context).also { instance = it }
            }
    }
}
