package com.motorguard.ivi.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.data.notifications.NotificationRelay
import com.motorguard.ivi.ui.theme.MotorGuard
import kotlinx.coroutines.delay

/**
 * The most recent notification, shown on Home because nothing else can show it.
 *
 * Deliberately one at a time and short-lived rather than a scrolling list: a dashboard is
 * glanceable, and a driver should not be reading a backlog. Ongoing notifications never appear
 * here at all — [NotificationRelay] filters them out, so the media transport row and running
 * foreground services stay silent.
 *
 * Nothing renders until the listener is allow-listed, which is a manual step:
 *
 *     adb shell cmd notification allow_listener \
 *         com.motorguard.ivi/com.motorguard.ivi.data.notifications.NotificationRelayService
 */
@Composable
fun NotificationBanner(modifier: Modifier = Modifier) {
    val latest by NotificationRelay.latest.collectAsStateWithLifecycle()
    val colors = MotorGuard.colors

    LaunchedEffect(latest) {
        if (latest != null) {
            delay(SHOW_MS)
            NotificationRelay.consumeLatest()
        }
    }

    AnimatedVisibility(
        visible = latest != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        // Held through the exit animation, which outlives the state going null.
        val shown = remember(latest) { latest }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.accent.copy(alpha = 0.14f))
                .clickable { NotificationRelay.consumeLatest() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = shown?.title.orEmpty(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!shown?.text.isNullOrBlank()) {
                    Text(
                        text = shown.text,
                        fontSize = 13.sp,
                        color = colors.onBaseDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Long enough to read two lines at a glance, short enough not to linger over the map. */
private const val SHOW_MS = 7_000L
