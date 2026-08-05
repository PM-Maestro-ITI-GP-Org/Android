package com.motorguard.ivi.media

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.motorguard.ivi.MainActivity
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.MediaSourceManager
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * The media player itself: an ExoPlayer that outlives the Activity.
 *
 * A [MediaLibraryService] rather than a plain `MediaSessionService` because on AAOS a media app
 * is expected to be *browsable* — the car's own media UI, the voice assistant and any other
 * controller can walk the library through [onGetChildren] without our Activity existing. The
 * browse tree is root → source → tracks, matching the source tabs.
 *
 * Background playback, audio focus, media-button handling and the notification all come from
 * media3; what is left for us is the player's configuration and the library tree.
 *
 * Nothing here talks to the UI directly. The Media tab and the Home widget both attach a
 * `MediaController` ([MediaConnection]) — which is also what makes the two impossible to get out
 * of sync, since they are reading one session rather than two copies of the state.
 */
@OptIn(UnstableApi::class)
class MotorGuardMediaService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null

    /** Tracks currently exposed under each source node, so a play request can be resolved. */
    private val loaded = mutableMapOf<MediaSourceId, List<Track>>()

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // true = ExoPlayer requests and honours audio focus for us: it ducks for the
                // navigation prompts and pauses for a phone call without any code here.
                /* handleAudioFocus = */ true,
            )
            // Pausing rather than continuing into silence when headphones/BT drop is the
            // behaviour every user already expects.
            .setHandleAudioBecomingNoisy(true)
            .build()

        player = exoPlayer
        session = MediaLibrarySession.Builder(this, exoPlayer, LibraryCallback())
            .setSessionActivity(openAppIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    /**
     * Stop the service when the user dismisses the UI with nothing playing. Without this the
     * empty session lingers in the notification shade, which on a head unit means a permanent
     * "MotorGuard" row that does nothing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        player?.release()
        session = null
        player = null
        scope.cancel()
        super.onDestroy()
    }

    /** Tapping the notification returns to the app rather than launching a second task. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(browsableItem(ROOT_ID, "MotorGuard"), params),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val manager = MediaSourceManager.get(this@MotorGuardMediaService)

            if (parentId == ROOT_ID) {
                // One browsable node per source tab — except video, which this service cannot
                // render (no surface, see PlaybackKind.VIDEO). Offering it to the car's media UI
                // or to the voice assistant would advertise something that plays as silence.
                return@future LibraryResult.ofItemList(
                    ImmutableList.copyOf(
                        manager.sources
                            .filterNot { it.playbackKind == PlaybackKind.VIDEO }
                            .map { browsableItem(sourceNodeId(it.id), it.label) },
                    ),
                    params,
                )
            }

            val sourceId = sourceIdFromNode(parentId)
                ?: return@future LibraryResult.ofItemList(ImmutableList.of(), params)

            val tracks = manager.tracks(sourceId)
            loaded[sourceId] = tracks
            LibraryResult.ofItemList(ImmutableList.copyOf(tracks.map { it.toMediaItem() }), params)
        }

        /**
         * Controllers hand back media items carrying only an id — the URI is stripped in transit
         * — so each one has to be resolved against the loaded library before the player can open
         * it. This is the step that is easy to miss and shows up as "playback starts and
         * immediately ends".
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.map { item ->
                if (item.localConfiguration != null) return@map item
                resolve(item.mediaId)?.toMediaItem() ?: item
            }.toMutableList()
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(COMMAND_SET_SOURCE, android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }

        /** Source switching runs through the session so the service can stop the old source. */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != COMMAND_SET_SOURCE) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val id = args.getString(KEY_SOURCE_ID)?.let { name ->
                runCatching { MediaSourceId.valueOf(name) }.getOrNull()
            } ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))

            scope.launch {
                // "Switching source pauses the previous source" — docs/04-media.md. There is one
                // player, so clearing it is what enforces the single audio-focus owner.
                player?.run {
                    pause()
                    clearMediaItems()
                }
                MediaSourceManager.get(this@MotorGuardMediaService).setActive(id)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun resolve(mediaId: String): Track? =
        loaded.values.firstNotNullOfOrNull { tracks -> tracks.firstOrNull { it.id == mediaId } }

    private fun browsableItem(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private companion object {
        const val ROOT_ID = "motorguard-root"
        const val SOURCE_PREFIX = "source:"

        fun sourceNodeId(id: MediaSourceId) = "$SOURCE_PREFIX${id.name}"

        fun sourceIdFromNode(nodeId: String): MediaSourceId? =
            nodeId.removePrefix(SOURCE_PREFIX)
                .takeIf { nodeId.startsWith(SOURCE_PREFIX) }
                ?.let { name -> runCatching { MediaSourceId.valueOf(name) }.getOrNull() }
    }
}

/** Custom session command used by the source switcher. */
const val COMMAND_SET_SOURCE = "com.motorguard.ivi.SET_SOURCE"
const val KEY_SOURCE_ID = "source_id"

/** Domain [Track] → media3 item. Kept here so the mapping has exactly one definition. */
@OptIn(UnstableApi::class)
fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artworkUri)
            .setTrackNumber(trackNumber)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )
    .build()

/** Media3 repeat constants ↔ our [com.motorguard.ivi.data.media.RepeatMode]. */
@OptIn(UnstableApi::class)
internal fun Int.toRepeatMode(): com.motorguard.ivi.data.media.RepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> com.motorguard.ivi.data.media.RepeatMode.ONE
    Player.REPEAT_MODE_ALL -> com.motorguard.ivi.data.media.RepeatMode.ALL
    else -> com.motorguard.ivi.data.media.RepeatMode.OFF
}

internal fun com.motorguard.ivi.data.media.RepeatMode.toPlayerRepeat(): Int = when (this) {
    com.motorguard.ivi.data.media.RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    com.motorguard.ivi.data.media.RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    com.motorguard.ivi.data.media.RepeatMode.OFF -> Player.REPEAT_MODE_OFF
}
