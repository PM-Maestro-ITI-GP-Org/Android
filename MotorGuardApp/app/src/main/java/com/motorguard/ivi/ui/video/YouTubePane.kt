package com.motorguard.ivi.ui.video

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // YouTube refuses to start playback from script unless this is off, which on a
            // touchscreen means every video needs two taps instead of one.
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // Keep navigation inside the pane: without this, a link hands off to an external
            // browser that does not exist on this image, and the tap appears to do nothing.
            webViewClient = WebViewClient()
            // Required for HTML5 fullscreen inside the page to work at all.
            webChromeClient = WebChromeClient()
            loadUrl(HOME_URL)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            // Leaving the tab must stop the audio, exactly as leaving local video does.
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { webView },
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

/** The mobile site: laid out for a touchscreen, and far lighter than the desktop one. */
private const val HOME_URL = "https://m.youtube.com/"
