package com.motorguard.ivi.ui.media.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.media.MediaConnection
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The Home now-playing widget from MeowScreen.dc.html: 56 dp cover, title, artist, a favourite
 * heart, and a transport row underneath.
 *
 * It reads the same [MediaConnection] the Media tab does, so what it shows is the actual player
 * — not a copy of its state — and it keeps updating while the driver is on any other tab or
 * while the app is in the background.
 *
 * @param onOpenMedia tapping the card jumps to the Media tab, per docs/03-home.md.
 */
@Composable
fun NowPlayingCard(
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val connection = remember { MediaConnection.get(context) }
    val playback by connection.state.collectAsStateWithLifecycle()

    val track = playback.track
    val artwork = rememberAlbumArt(track, sizeDp = 128)

    val colors = MotorGuard.colors

    GlassCard(
            modifier = modifier.clickable(onClick = onOpenMedia),
            shape = RoundedCornerShape(26.dp),
            padding = PaddingValues(horizontal = 26.dp, vertical = 20.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AlbumThumbnail(artwork = artwork, sizeDp = 56)
                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        // Slide the text on track change so a glance at Home shows something
                        // moved, rather than silently different words.
                        AnimatedContent(
                            targetState = track?.title ?: "Nothing playing",
                            transitionSpec = {
                                (slideInVertically { it / 2 } + fadeIn())
                                    .togetherWith(slideOutVertically { -it / 2 } + fadeOut())
                            },
                            label = "now-playing-title",
                        ) { title ->
                            Text(
                                text = title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = track?.artist?.takeIf { it.isNotBlank() } ?: "Tap to browse",
                            fontSize = 13.sp,
                            color = colors.onBaseDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    FavoriteButton(enabled = playback.hasTrack)
                }

                Spacer(Modifier.height(14.dp))
                MiniProgress(fraction = playback.progress)

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniControl(
                        icon = Icons.Filled.SkipPrevious,
                        description = "Previous track",
                        enabled = playback.hasTrack && playback.canSkip,
                        onClick = connection::previous,
                    )
                    Spacer(Modifier.width(8.dp))
                    PlayPauseButton(
                        isPlaying = playback.isPlaying,
                        enabled = playback.hasTrack,
                        onClick = connection::playPause,
                        diameter = 48.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    MiniControl(
                        icon = Icons.Filled.SkipNext,
                        description = "Next track",
                        enabled = playback.hasTrack && playback.canSkip,
                        onClick = connection::next,
                    )
                }

                // Compact, because the widget has to stay the same height as the Vehicle card
                // beside it — but present, since reaching volume from Home is the whole point of
                // a now-playing widget.
                Spacer(Modifier.height(10.dp))
                VolumeBar(compact = true)
            }
        }
    }

/** Thin progress line under the metadata — scaled, never re-laid out. */
@Composable
private fun MiniProgress(fraction: Float) {
    val colors = MotorGuard.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.glassBorder),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .graphicsLayer {
                    scaleX = fraction.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clip(RoundedCornerShape(2.dp))
                .background(colors.accent),
        )
    }
}

@Composable
private fun MiniControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MotorGuard.colors
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else colors.onBaseDim,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * Favourite toggle. Local-only for now — there is no library to persist a "liked" flag to yet,
 * so this deliberately does not pretend to sync anywhere.
 *
 * docs/03-home.md marks it disabled while the vehicle is moving; wire that to `CarUxRestrictions`
 * once `CarDataRepository` exists.
 */
@Composable
private fun FavoriteButton(enabled: Boolean) {
    val colors = MotorGuard.colors
    var favorite by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(enabled = enabled) { favorite = !favorite },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
            tint = if (favorite) MotorGuard.colors.accent else colors.onBaseDim,
            modifier = Modifier.size(24.dp),
        )
    }
}
