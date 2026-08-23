package com.motorguard.ivi.ui.web

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * A web-backed source, rendered in place.
 *
 * The view comes from [session] rather than being built here, so it survives leaving the tab —
 * see [WebSession]. [blocked] is the driving rule: it applies to anything showing moving
 * pictures, and where the pictures came from does not change what they do to the driver's
 * attention.
 */
@Composable
fun WebPane(
    session: WebSession,
    blocked: Boolean,
    blockedMessage: String,
    modifier: Modifier = Modifier,
) {
    // NOT an early "if (blocked) { Blocked(); return }": that shape means Compose sees a
    // *different* set of composable calls on each recomposition path (remember/DisposableEffect/
    // AndroidView only some of the time, at the same call site another time), which is exactly
    // the pattern most often cited for slot-table corruption crashes. blocked can flip mid-
    // transition -- it defaults to false and is only set to its real value once DrivingState's
    // flow delivers its first reading, so a tab opened right as that first reading arrives can
    // hit this composable twice with different values before it settles. An explicit if/else
    // keeps the group structure for the WebView branch identical regardless of which branch
    // runs, since Compose still has to reconcile the two against each other either way.
    if (blocked) {
        Blocked(blockedMessage, modifier)
    } else {
        val context = LocalContext.current
        val webView = remember(session) { session.acquire(context) }

        DisposableEffect(webView) {
            session.attach(webView)
            onDispose { session.detach(webView) }
        }

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                // The same instance is reattached across visits, so any previous parent has to be
                // let go of first or Android refuses to add it.
                factory = {
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Blocked(message: String, modifier: Modifier = Modifier) {
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
            text = "Paused while driving",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            fontSize = 13.sp,
            color = MotorGuard.colors.onBaseDim,
            textAlign = TextAlign.Center,
        )
    }
}
