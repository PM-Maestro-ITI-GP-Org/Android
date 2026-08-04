package com.motorguard.ivi.ui.media.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.motorguard.ivi.data.media.AlbumArtLoader
import com.motorguard.ivi.data.media.Track
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * Loads artwork for [track].
 *
 * The previous bitmap stays on screen for the duration of the load, and is replaced only once
 * the new one has resolved — clearing first would flash the cover, and with it every accent on
 * screen, back to the fallback on each track change.
 */
@Composable
fun rememberAlbumArt(track: Track?, sizeDp: Int = 512): Bitmap? {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var art by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(track?.id) {
        art = AlbumArtLoader.load(context, track, (sizeDp * density).toInt())
    }
    return art
}

/**
 * The album art block from MeowScreen.dc.html: two glass cards fanned out behind the cover at
 * ±9°, suggesting a stack of records.
 *
 * The fan is drawn only when there is real artwork — over the empty-state placeholder it reads
 * as clutter rather than depth.
 */
@Composable
fun AlbumArtStack(
    artwork: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val shape = RoundedCornerShape(22.dp)

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        if (artwork != null) {
            FanCard(rotation = -9f, offsetFraction = -0.13f, shape = shape)
            FanCard(rotation = 9f, offsetFraction = 0.13f, shape = shape)
        }

        Crossfade(targetState = artwork, label = "album-art") { current ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(colors.glassSoft)
                    .border(1.dp, colors.glassBorder, shape),
                contentAlignment = Alignment.Center,
            ) {
                if (current != null) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = "Album art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ArtPlaceholder()
                }
            }
        }
    }
}

@Composable
private fun FanCard(rotation: Float, offsetFraction: Float, shape: RoundedCornerShape) {
    val colors = MotorGuard.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationZ = rotation
                translationX = size.width * offsetFraction
                scaleX = 0.86f
                scaleY = 0.86f
            }
            .clip(shape)
            .background(colors.glassSoft)
            .border(1.dp, colors.glassBorder, shape),
    )
}

/** The design's "album art" empty slot. */
@Composable
private fun ArtPlaceholder() {
    val colors = MotorGuard.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.Album,
            contentDescription = null,
            tint = colors.onBaseDim,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "ALBUM ART",
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onBaseDim,
        )
    }
}

/** Small square cover for the queue rows and the Home widget. */
@Composable
fun AlbumThumbnail(
    artwork: Bitmap?,
    sizeDp: Int,
    modifier: Modifier = Modifier,
    cornerDp: Int = 15,
) {
    val colors = MotorGuard.colors
    val shape = RoundedCornerShape(cornerDp.dp)
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(colors.glassSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Album,
                contentDescription = null,
                tint = colors.onBaseDim,
                modifier = Modifier.size((sizeDp * 0.4f).dp),
            )
        }
    }
}
