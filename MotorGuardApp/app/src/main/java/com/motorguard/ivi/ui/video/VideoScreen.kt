package com.motorguard.ivi.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.media.components.VideoPane
import com.motorguard.ivi.ui.theme.MotorGuard
import com.motorguard.ivi.ui.web.WebPane
import com.motorguard.ivi.ui.web.WebSession

/**
 * The Videos surface: player on the left, library on the right, and a full-screen mode.
 *
 * Video was a tab inside Media, which put it a level deeper than it deserved — it is not a
 * *source of music*, it is a different thing to do with the screen. It is a rail destination of
 * its own now, and Media's source bar no longer lists it.
 *
 * @param onFullscreenChange lets the host chrome (rail and status bar) get out of the way.
 */
@Composable
fun VideoScreen(
    viewModel: VideoViewModel = viewModel(),
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var fullscreen by remember { mutableStateOf(false) }
    var youtube by remember { mutableStateOf(WebSession.YouTube.selected) }

    // Leaving a video full-screen and navigating away would strand the chrome hidden, so the
    // host is always told the truth about the current mode, including on the way out.
    androidx.compose.runtime.DisposableEffect(fullscreen) {
        onFullscreenChange(fullscreen)
        onDispose { onFullscreenChange(false) }
    }

    if (fullscreen) {
        FullscreenPlayer(
            state = state,
            youtube = youtube,
            onExit = { fullscreen = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 22.dp),
    ) {
        SourceToggle(
            youtube = youtube,
            onSelect = { youtube = it; WebSession.YouTube.selected = it },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1.35f)
                    .fillMaxHeight(),
            ) {
                if (youtube) {
                    WebPane(
                        session = WebSession.YouTube,
                        blocked = state.isMoving,
                        blockedMessage = "YouTube resumes when the car is stopped",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    VideoPane(
                        track = state.selected,
                        isMoving = state.isMoving,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Only offered when there is something to expand — a full-screen button over an
                // empty pane is a promise the screen cannot keep.
                if ((youtube || state.selected != null) && !state.isMoving) {
                    FullscreenButton(
                        expanded = false,
                        onClick = { fullscreen = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    )
                }
            }

            if (!youtube) {
                VideoLibrary(
                    state = state,
                    onPlay = viewModel::play,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * Full-screen playback: the video, and nothing else.
 *
 * Black rather than the glass surface, because at this size the backdrop is most of the screen
 * and anything but black is a tint over the picture. The control fades in on tap rather than
 * sitting there permanently — the whole point of this mode is to see the film.
 */
@Composable
private fun FullscreenPlayer(
    state: VideoUiState,
    youtube: Boolean,
    onExit: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }

    // Auto-hide, then reveal on tap — but never over the WebView.
    //
    // "Tap to bring the control back" relies on the tap reaching this Box, and a WebView
    // consumes its own touches: on the YouTube pane the reveal never fires, so once the exit
    // button faded there was no way out of full screen at all. Better a small button always in
    // the corner than a trap.
    androidx.compose.runtime.LaunchedEffect(controlsVisible, youtube) {
        if (controlsVisible && !youtube) {
            kotlinx.coroutines.delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { controlsVisible = !controlsVisible },
        contentAlignment = Alignment.Center,
    ) {
        if (youtube) {
            WebPane(
                session = WebSession.YouTube,
                blocked = state.isMoving,
                blockedMessage = "YouTube resumes when the car is stopped",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            VideoPane(
                track = state.selected,
                isMoving = state.isMoving,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = youtube || controlsVisible || state.isMoving,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            FullscreenButton(
                expanded = true,
                onClick = onExit,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun FullscreenButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            contentDescription = if (expanded) "Exit full screen" else "Full screen",
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun VideoLibrary(
    state: VideoUiState,
    onPlay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        padding = PaddingValues(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 10.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
                Text(
                    text = "Videos",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.summary,
                    fontSize = 12.sp,
                    color = colors.onBaseDim,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.loading -> Centered("Scanning…")
                    state.videos.isEmpty() -> Centered("No videos on this device or drive")
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(state.videos, key = { _, v -> v.id }) { index, video ->
                            VideoRow(
                                title = video.title,
                                meta = video.artist,
                                isCurrent = video.id == state.selected?.id,
                                onClick = { onPlay(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoRow(
    title: String,
    meta: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val colors = MotorGuard.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) colors.accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Text(text = meta, fontSize = 13.sp, color = colors.onBaseDim)
            }
        }
    }
}

@Composable
private fun Centered(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, fontSize = 15.sp, color = MotorGuard.colors.onBaseDim)
    }
}

private const val CONTROLS_TIMEOUT_MS = 3_000L

/**
 * Local library ↔ YouTube.
 *
 * Two sources of the same thing — moving pictures — so they belong in one switcher, the way the
 * Media screen groups its audio sources. Deliberately not in the navigation rail: the rail is
 * for destinations, and YouTube is a place to get video from, not a separate activity.
 */
@Composable
private fun SourceToggle(
    youtube: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(colors.glassBorder.copy(alpha = 0.35f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToggleChip("Library", selected = !youtube) { onSelect(false) }
        ToggleChip("YouTube", selected = youtube) { onSelect(true) }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MotorGuard.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}
