package com.motorguard.ivi.ui.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.MediaSourceManager
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.media.components.AlbumArtStack
import com.motorguard.ivi.ui.media.components.Scrubber
import com.motorguard.ivi.ui.media.components.SourceTabs
import com.motorguard.ivi.ui.media.components.TrackRow
import com.motorguard.ivi.ui.media.components.TransportBar
import com.motorguard.ivi.ui.media.components.VolumeBar
import com.motorguard.ivi.ui.media.components.VideoPane
import com.motorguard.ivi.ui.media.components.rememberAlbumArt
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard
import kotlinx.coroutines.delay

/**
 * The Media surface: now-playing on the left, the queue on the right, matching
 * MeowScreen.dc.html.
 *
 * Accents here — the progress fill, the active source tab, the equaliser, the shuffle and
 * repeat states — come from `MotorGuard.colors.accent`, which [com.motorguard.ivi.ui.theme.MotorGuardTheme]
 * derives from the current cover for the whole app.
 */
@Composable
fun MediaScreen(viewModel: MediaViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sources = remember { MediaSourceManager.get(context) }

    // --- runtime media-read permissions -------------------------------------------------------
    // Audio and video are separate grants from API 33 on, and asking for only the first is why
    // the Videos tab came up empty on a device that had videos. Requested together so the driver
    // sees one prompt rather than two.
    var permissionGranted by remember { mutableStateOf(context.hasMediaPermissions()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionGranted = results.values.all { it }
        // The library query returns empty without them, so the list has to be re-read on grant.
        if (results.values.any { it }) viewModel.refresh()
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(mediaPermissions())
    }

    val artwork = rememberAlbumArt(state.playback.track)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 22.dp),
    ) {
        SourceTabs(
            sources = sources.sources,
            active = state.activeSource,
            availableIds = state.availability.filter { it.available }.map { it.id }.toSet(),
            onSelect = viewModel::selectSource,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        UsbNotice(
            notice = state.notice,
            onOpenUsb = { viewModel.selectSource(MediaSourceId.USB) },
            onOpenBluetooth = { viewModel.selectSource(MediaSourceId.BLUETOOTH) },
            onDismiss = viewModel::dismissNotice,
        )

        Spacer(Modifier.height(14.dp))

        Row(
            // weight(1f), NOT fillMaxSize(). A Column measures its children with an unbounded
            // main axis, and fillMaxSize passes that Infinity straight through to its children —
            // which makes the LazyColumn in the queue pane throw "Vertically scrollable component
            // was measured with an infinity maximum height". weight() is what actually bounds
            // this row to the leftover space.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Video replaces the now-playing card rather than sitting beside it: the two are
            // both "what is playing", and a cover thumbnail next to a running film is noise.
            if (state.activeSource == MediaSourceId.VIDEO) {
                VideoPane(
                    track = state.selectedVideo,
                    isMoving = state.isMoving,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight(),
                )
            } else {
                NowPlayingPane(
                    state = state,
                    artwork = artwork,
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight(),
                )
            }
            QueuePane(
                state = state,
                onPlayTrack = {
                    if (state.activeSource == MediaSourceId.VIDEO) {
                        viewModel.playVideo(it)
                    } else {
                        viewModel.playTrack(it)
                    }
                },
                onSearch = viewModel::searchStations,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * The "a drive appeared" banner.
 *
 * Slides in over the source tabs rather than replacing anything, and offers the switch instead of
 * performing it — see [MediaViewModel.onUsbEvent] for why a head unit must not change what is
 * playing on its own. Auto-dismisses so a driver who is not looking does not come back to a
 * stale message.
 */
@Composable
private fun UsbNotice(
    notice: MediaNotice?,
    onOpenUsb: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_DURATION_MS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        // Held after dismissal so the exit animation has something to draw.
        val shown = remember(notice) { notice }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MotorGuard.colors.accent.copy(alpha = 0.14f))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (shown) {
                    is MediaNotice.BluetoothConnected,
                    is MediaNotice.BluetoothDisconnected,
                    -> Icons.Filled.Bluetooth
                    else -> Icons.Filled.Usb
                },
                contentDescription = null,
                tint = MotorGuard.colors.accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = when (shown) {
                    is MediaNotice.UsbMounted -> "${shown.label} connected"
                    MediaNotice.UsbRemoved -> "USB drive removed"
                    // Named rather than a generic "phone connected": a car often has more than
                    // one paired handset and the driver wants to know which one won.
                    is MediaNotice.BluetoothConnected -> "${shown.name} connected"
                    is MediaNotice.BluetoothDisconnected -> "${shown.name} disconnected"
                    null -> ""
                },
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            when (shown) {
                is MediaNotice.UsbMounted -> TextButton(onClick = { onOpenUsb(); onDismiss() }) {
                    Text("Open", color = MotorGuard.colors.accent, fontWeight = FontWeight.SemiBold)
                }

                is MediaNotice.BluetoothConnected -> TextButton(
                    onClick = { onOpenBluetooth(); onDismiss() },
                ) {
                    Text("Open", color = MotorGuard.colors.accent, fontWeight = FontWeight.SemiBold)
                }

                else -> Unit
            }
        }
    }
}

private const val NOTICE_DURATION_MS = 6_000L

@Composable
private fun NowPlayingPane(
    state: MediaUiState,
    artwork: android.graphics.Bitmap?,
    viewModel: MediaViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val playback = state.playback
    val track = playback.track

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        padding = PaddingValues(24.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The cover is bounded by BOTH axes, and it is the thing that yields.
            //
            // Sizing it from width alone works at 1920x720 and pushes the controls off a shorter
            // panel — the README also targets 1024x600, and a phone in landscape is shorter
            // still. So the text, scrubber and 76 dp transport row claim their space first and
            // the cover takes what is left; below MIN_ART there is nothing worth showing and it
            // drops out entirely. Losing the artwork is a real cost, but losing play/pause is a
            // broken screen.
            val artSize = minOf(maxWidth * 0.54f, maxHeight - CONTROLS_HEIGHT, MAX_ART)
            val showArt = artSize >= MIN_ART
            val tight = maxHeight < COMFORTABLE_HEIGHT
            // Captured out here: inside the Column, BoxWithConstraints' receiver is shadowed.
            val narrow = maxWidth < NARROW_PANE

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (showArt) {
                    AlbumArtStack(artwork = artwork, modifier = Modifier.width(artSize))
                    Spacer(Modifier.height(if (tight) 12.dp else 26.dp))
                }
                Text(
                    text = track?.title ?: "Nothing playing",
                    fontSize = if (tight) 22.sp else 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = track?.subtitle?.takeIf { it.isNotBlank() } ?: "Pick a track to start",
                    fontSize = 14.sp,
                    color = colors.onBaseDim,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(if (tight) 10.dp else 22.dp))
                Scrubber(
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    enabled = playback.hasTrack && playback.canSeek,
                    onSeek = viewModel::seekTo,
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth(0.82f),
                )

                Spacer(Modifier.height(if (tight) 6.dp else 22.dp))
                TransportBar(
                    isPlaying = playback.isPlaying,
                    shuffle = playback.shuffle,
                    repeat = playback.repeat,
                    enabled = playback.hasTrack,
                    // Radio has no queue to skip through — docs/04-media.md disables these there.
                    canSkip = playback.canSkip && playback.playbackKind != PlaybackKind.TUNER,
                    onPlayPause = viewModel::playPause,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    onShuffle = viewModel::toggleShuffle,
                    onRepeat = viewModel::cycleRepeat,
                    compact = tight || narrow,
                )

                // Volume lives below the transport rather than beside it: it applies to whatever
                // is playing, including the phone over Bluetooth, so it is not a per-track
                // control and should not sit among the ones that are.
                Spacer(Modifier.height(if (tight) 8.dp else 18.dp))
                VolumeBar(
                    compact = tight || narrow,
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth(0.82f),
                )
            }
        }
    }
}

/** What the title, subtitle, scrubber and 76 dp transport row need below the cover. */
private val CONTROLS_HEIGHT = 165.dp

/**
 * Station search.
 *
 * Search-as-you-type, debounced in the ViewModel — the alternative is a submit button, and a
 * driver should not have to hunt for one. Single line and IME "search" so the on-screen keyboard
 * closes on the enter key rather than inserting a newline into the query.
 */
@Composable
private fun StationSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search stations", fontSize = 15.sp) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MotorGuard.colors.onBaseDim,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = MotorGuard.colors.onBaseDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MotorGuard.colors.accent,
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
            cursorColor = MotorGuard.colors.accent,
        ),
    )
}

/** Below this the pane switches to reduced spacing and a smaller title. */
private val COMFORTABLE_HEIGHT = 420.dp

/** Below this width the transport row cannot fit five 76 dp targets. */
private val NARROW_PANE = 380.dp

/** Smaller than this and the cover is not worth the space it costs the controls. */
private val MIN_ART = 64.dp
private val MAX_ART = 232.dp

@Composable
private fun QueuePane(
    state: MediaUiState,
    onPlayTrack: (Int) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val unavailable = state.availabilityOf(state.activeSource)?.takeIf { !it.available }

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        padding = PaddingValues(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 10.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.queue.title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.queue.subtitle,
                        fontSize = 12.sp,
                        color = colors.onBaseDim,
                    )
                }
            }

            // Radio is a directory of thousands of stations, so it is the one source where
            // browsing a list is useless without a way to narrow it.
            if (state.activeSource == MediaSourceId.RADIO && unavailable == null) {
                StationSearchField(
                    query = state.searchQuery,
                    onQueryChange = onSearch,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                )
            }

            // The content area takes the leftover height so the header keeps its own. Same
            // reason as the row above: inside a Column, fillMaxSize is not a bound.
            Box(modifier = Modifier.weight(1f)) {
                when {
                    unavailable != null -> EmptyState(message = unavailable.emptyMessage)
                    state.loading -> EmptyState(message = "Scanning…")
                    state.tracks.isEmpty() -> EmptyState(message = emptyLibraryMessage(state.activeSource))
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
                            TrackRow(
                                track = track,
                                index = index,
                                isCurrent = track.id == state.playback.track?.id,
                                isPlaying = state.playback.isPlaying,
                                onClick = { onPlayTrack(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    val colors = MotorGuard.colors
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = colors.onBaseDim,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                fontSize = 15.sp,
                color = colors.onBaseDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun emptyLibraryMessage(source: MediaSourceId): String = when (source) {
    MediaSourceId.LOCAL -> "No music on this device"
    MediaSourceId.USB -> "No music on the drive"
    MediaSourceId.BLUETOOTH -> "Bluetooth shows the phone's current track only"
    MediaSourceId.RADIO -> "No stations found — try another search"
    MediaSourceId.VIDEO -> "No videos on this device or drive"
}

/**
 * Storage permissions were split by media type in API 33: audio and video are separate grants,
 * and one does not imply the other. Below 33 a single storage permission covers both.
 */
private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun Context.hasMediaPermissions(): Boolean = mediaPermissions().all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}
