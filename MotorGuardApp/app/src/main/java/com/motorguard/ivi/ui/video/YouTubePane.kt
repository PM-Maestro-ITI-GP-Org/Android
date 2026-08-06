package com.motorguard.ivi.ui.video

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * YouTube, in a WebView.
 *
 * A WebView rather than an API client because there is no other option here: the Data API does
 * not serve video streams, the official player SDK needs Play Services, and this is a bare AOSP
 * image. The mobile site is what a head unit can actually show.
 *
 * The same driving rule as local video applies — [isMoving] blanks it — and for the same reason:
 * this is a *screen* showing moving pictures, and where the content came from does not change
 * what it does to the driver's attention.
 *
 * The WebView is created once and kept across recompositions. Rebuilding it would reload the
 * page and lose the user's place, which on a touchscreen is the difference between a browser and
 * an annoyance.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePane(
    isMoving: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isMoving) {
        Blocked(modifier)
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    // Held by [YouTubeSession], not by this composable: destroying it here is what threw away
    // the sign-in and the current video every time the tab was left.
    val webView = remember { YouTubeSession.acquire(context) }

    DisposableEffect(webView) {
        YouTubeSession.resume(webView)
        onDispose {
            // Detach and pause rather than destroy — playback stops, the session survives.
            YouTubeSession.release(webView)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            // The same instance is reattached across visits, so any previous parent has to be
            // let go of first or Android refuses to add it.
            factory = { ctx ->
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Same message and reasoning as the local video pane — one rule, stated once per surface. */
@Composable
private fun Blocked(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
            text = "YouTube resumes when the car is stopped",
            fontSize = 13.sp,
            color = MotorGuard.colors.onBaseDim,
            textAlign = TextAlign.Center,
        )
    }
}

