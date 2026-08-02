package com.motorguard.ivi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.media.components.NowPlayingCard
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * Home, laid out as the three glass cards from the design: Map · Vehicle · Weather + Media.
 *
 * **Only the now-playing card is implemented here.** Home belongs to owner A (docs/03-home.md);
 * the gauge rings, mini-map and weather widget are theirs to build. What this file provides is
 * the card skeleton in the right proportions plus the real media widget, so the two can be
 * developed without colliding — the placeholder slots are named for what goes in them.
 */
@Composable
fun HomeScreen(onOpenMedia: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PlaceholderSlot(
            title = "Map",
            note = "Owner A · mini-map + ETA",
            modifier = Modifier
                .weight(1.35f)
                .fillMaxHeight(),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PlaceholderSlot(
                title = "Vehicle",
                note = "Owner A · battery + range rings",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            NowPlayingCard(
                onOpenMedia = onOpenMedia,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A named empty card, so the layout reads as intentional rather than unfinished. */
@Composable
private fun PlaceholderSlot(title: String, note: String, modifier: Modifier = Modifier) {
    val colors = MotorGuard.colors
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        padding = PaddingValues(24.dp),
        soft = true,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBaseDim,
                )
                Text(text = note, fontSize = 12.sp, color = colors.onBaseDim)
            }
        }
    }
}
