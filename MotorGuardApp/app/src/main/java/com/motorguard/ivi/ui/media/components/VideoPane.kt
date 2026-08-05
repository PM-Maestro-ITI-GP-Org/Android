package com.motorguard.ivi.ui.media.components

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.PlaybackSnapshot
import com.motorguard.ivi.data.media.Track
import com.motorguard.ivi.media.VideoPlayback
import com.motorguard.ivi.ui.theme.MotorGuard
import kotlinx.coroutines.delay

/**
 * The video surface, with its own player.
 *
 * Video does not go through the shared `MediaLibraryService`. Rendering needs a surface and a
 * `MediaController` cannot be given one — it is talking to a session in another process — so
 * this pane owns a plain [ExoPlayer] for as long as it is composed. That is also the behaviour
 * you want: leaving the screen releases the player and the video stops, where leaving the screen
 * with music playing must not stop the music.
 *
 * A [SurfaceView] rather than a `TextureView`: on the Pi's VideoCore VII a SurfaceView goes
 * through the hardware overlay and never touches the GL composition this UI is already spending
 * its budget on.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPane(
    track: Track?,
    isMoving: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // Taking focus here is what pauses the music service — one audio owner at a time,
                // the same rule docs/04-media.md sets for the audio sources.
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    // Report to the rest of the app, so the Home now-playing card and the transport bar know a
    // video is what is currently making sound. See [VideoPlayback].
    DisposableEffect(player) {
        val controls = object : VideoPlayback.Controls {
            override fun playPause() {
                if (player.isPlaying) player.pause() else player.play()
            }

            override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
        }
        VideoPlayback.attach(controls)

        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                VideoPlayback.update(snapshotOf(p, track))
            }
        }
        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
            VideoPlayback.detach(controls)
            player.release()
        }
    }

    // The player reports position only when something else changes, so the card's progress would
    // otherwise freeze mid-film.
    LaunchedEffect(player) {
        while (true) {
            delay(POSITION_TICK_MS)
            if (player.isPlaying) VideoPlayback.update(snapshotOf(player, track))
        }
    }

    LaunchedEffect(track?.id) {
        val uri = track?.uri
        if (uri == null) {
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    // The rule is on playback, not just on the picture: muting the video but leaving it running
    // would still have it demanding attention the moment the car stops.
    LaunchedEffect(isMoving) {
        if (isMoving) player.pause()
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            isMoving -> Blocked()
            track == null -> Idle()
            else -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            player.setVideoSurfaceView(this)
                        }
                    },
                    // Re-attached on every recomposition that replaces the view: a surface handed
                    // to a released player renders nothing and gives no error.
                    update = { player.setVideoSurfaceView(it) },
                )
            }
        }
    }
}

/**
 * The pane's player as a [PlaybackSnapshot].
 *
 * [PlaybackKind.VIDEO] rather than LOCAL_PLAYER so the transport bar knows these buttons go to
 * the pane's player and not to the session — the same distinction the Bluetooth mirror draws.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun snapshotOf(player: Player, track: Track?): PlaybackSnapshot? {
    if (track == null) return null
    return PlaybackSnapshot(
        track = track.copy(
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L)
                ?: track.durationMs,
        ),
        isPlaying = player.isPlaying,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L)
            ?: track.durationMs,
        source = MediaSourceId.VIDEO,
        playbackKind = PlaybackKind.VIDEO,
        canSeek = true,
        // One item at a time: the pane opens what the driver picked, it is not a queue.
        canSkip = false,
    )
}

private const val POSITION_TICK_MS = 500L

/** Shown while the car is moving. Says why, so it does not read as a failure. */
@Composable
private fun Blocked() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.NoPhotography,
            contentDescription = null,
            tint = MotorGuard.colors.caution,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Video paused while driving",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Playback resumes when the car is stopped",
            fontSize = 13.sp,
            color = MotorGuard.colors.onBaseDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Idle() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Videocam,
            contentDescription = null,
            tint = MotorGuard.colors.onBaseDim,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Pick a video to play",
            fontSize = 15.sp,
            color = MotorGuard.colors.onBaseDim,
        )
    }
}
