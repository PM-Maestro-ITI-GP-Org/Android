package com.motorguard.ivi.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.MediaSourceManager
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.PlaybackSnapshot
import com.motorguard.ivi.data.media.RepeatMode
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
 * The app's handle on the playback service.
 *
 * A process-wide singleton on purpose: the Media tab and the Home now-playing widget are two
 * views of one player, and giving each its own controller is how they end up showing different
 * tracks. They observe the same [state].
 *
 * All controller access is on the main thread — media3 requires it, and this is one of those
 * APIs where violating that yields intermittent misbehaviour rather than a clean exception.
 */
@OptIn(UnstableApi::class)
class MediaConnection private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controller: MediaController? = null
    private var positionTicker: Job? = null

    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    /**
     * The phone, when Bluetooth is the active source.
     *
     * Bluetooth is the one source whose audio we do not own, so its state cannot come from our
     * player — see [BluetoothSessionMirror]. Keeping it beside the controller rather than behind
     * some common abstraction is deliberate: the two really are different things, and the only
     * place that difference matters is [publish] and the transport methods.
     */
    private val bluetooth = BluetoothSessionMirror.get(appContext)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    private val isBluetooth: Boolean
        get() = MediaSourceManager.get(appContext).active.value == MediaSourceId.BLUETOOTH

    init {
        scope.launch { connect() }
        scope.launch {
            // The active source is owned by MediaSourceManager, not by the player, so it has to
            // be folded in separately.
            MediaSourceManager.get(appContext).active.collect { id ->
                // Re-attaching on entry is what makes the tab work for a phone paired after boot,
                // when the first attempt at startup had nothing to attach to.
                if (id == MediaSourceId.BLUETOOTH) bluetooth.connect()
                publish()
            }
        }
        scope.launch {
            // The mirror updates on the phone's schedule, not ours, so its snapshots have to be
            // forwarded as they arrive rather than only when our own player emits an event.
            bluetooth.state.collect { if (isBluetooth) _state.value = it }
        }
    }

    private suspend fun connect() {
        val token = SessionToken(
            appContext,
            ComponentName(appContext, MotorGuardMediaService::class.java),
        )
        val connected = runCatching {
            MediaController.Builder(appContext, token).buildAsync().await()
        }.getOrNull() ?: return

        controller = connected
        connected.addListener(listener)
        publish()
    }

    // ---------------------------------------------------------------- transport

    // Each of these goes to the phone instead of our player while Bluetooth is the source. The
    // buttons are the same buttons; only the thing on the other end of them changes.

    fun playPause() {
        if (isBluetooth) return bluetooth.playPause()
        withController { if (isPlaying) pause() else play() }
    }

    /**
     * Restart the current track, or step back if we are already near its start — the behaviour
     * docs/04-media.md specifies, and the one every physical head unit has.
     */
    fun previous() {
        if (isBluetooth) return bluetooth.previous()
        withController {
            if (currentPosition > RESTART_THRESHOLD_MS) seekTo(0) else seekToPrevious()
        }
    }

    fun next() {
        if (isBluetooth) return bluetooth.next()
        withController { seekToNext() }
    }

    fun seekTo(positionMs: Long) {
        if (isBluetooth) return bluetooth.seekTo(positionMs)
        withController { seekTo(positionMs) }
    }

    fun toggleShuffle() = withController { shuffleModeEnabled = !shuffleModeEnabled }

    /** off → all → one, then back. */
    fun cycleRepeat() = withController {
        repeatMode = when (repeatMode.toRepeatMode()) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }.toPlayerRepeat()
    }

    /** Replace the queue with [tracks] and start at [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int) = withController {
        if (tracks.isEmpty()) return@withController
        setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(tracks.indices), 0L)
        prepare()
        play()
    }

    /** Switch source. Routed through the session so the service can stop the previous one. */
    fun setSource(id: MediaSourceId) {
        val active = controller ?: run {
            MediaSourceManager.get(appContext).setActive(id)
            return
        }
        active.sendCustomCommand(
            SessionCommand(COMMAND_SET_SOURCE, Bundle.EMPTY),
            Bundle().apply { putString(KEY_SOURCE_ID, id.name) },
        )
        MediaSourceManager.get(appContext).setActive(id)
    }

    // ---------------------------------------------------------------- state

    private fun publish() {
        val active = controller
        val manager = MediaSourceManager.get(appContext)
        val sourceId = manager.active.value
        val kind = manager.source(sourceId).playbackKind

        // Our player holds nothing while the phone is the source, so publishing from it here
        // would blank the card between the mirror's own emissions.
        if (sourceId == MediaSourceId.BLUETOOTH) {
            _state.value = bluetooth.state.value
            positionTicker?.cancel()
            return
        }

        if (active == null) {
            _state.value = PlaybackSnapshot(source = sourceId, playbackKind = kind)
            return
        }

        _state.value = PlaybackSnapshot(
            track = active.currentMediaItem?.toTrack(sourceId, active.duration),
            isPlaying = active.isPlaying,
            positionMs = active.currentPosition.coerceAtLeast(0L),
            durationMs = active.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            shuffle = active.shuffleModeEnabled,
            repeat = active.repeatMode.toRepeatMode(),
            queueIndex = active.currentMediaItemIndex,
            source = sourceId,
            playbackKind = kind,
            canSeek = kind != PlaybackKind.TUNER && active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            canSkip = kind != PlaybackKind.TUNER,
        )

        // The player reports position only when something else changes, so a ticker is the only
        // way the scrubber moves during steady playback. It runs solely while playing — a
        // paused screen has nothing to update.
        positionTicker?.cancel()
        if (active.isPlaying) {
            positionTicker = scope.launch {
                while (true) {
                    delay(POSITION_TICK_MS)
                    val live = controller ?: break
                    _state.value = _state.value.copy(
                        positionMs = live.currentPosition.coerceAtLeast(0L),
                    )
                }
            }
        }
    }

    private inline fun withController(block: MediaController.() -> Unit) {
        controller?.let(block)
    }

    private fun MediaItem.toTrack(source: MediaSourceId, durationMs: Long) = Track(
        id = mediaId,
        title = mediaMetadata.title?.toString().orEmpty().ifBlank { "Unknown title" },
        artist = mediaMetadata.artist?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        durationMs = durationMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
        uri = localConfiguration?.uri,
        artworkUri = mediaMetadata.artworkUri,
        source = source,
        trackNumber = mediaMetadata.trackNumber,
    )

    companion object {
        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val POSITION_TICK_MS = 500L

        @Volatile
        private var instance: MediaConnection? = null

        fun get(context: Context): MediaConnection =
            instance ?: synchronized(this) {
                instance ?: MediaConnection(context).also { instance = it }
            }
    }
}
