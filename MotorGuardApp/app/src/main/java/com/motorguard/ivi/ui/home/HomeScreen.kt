package com.motorguard.ivi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.ui.components.GlassCard

/**
 * Owner A's glanceable hub, per docs/03-home.md: a big **Map** card next to a stack of **Vehicle**,
 * **Weather** and **Media** (now-playing) tiles — the doc's "last two equal height" is the Weather
 * and Media pair sharing the bottom row.
 *
 * Skeleton stage on purpose: these are four EMPTY [GlassCard]s that only stake out the layout. The
 * owner drops the real widgets (gauge rings, now-playing transport, weather) into them later — so
 * there is deliberately no text, icon or data inside. Styling comes entirely from the app card
 * ([GlassCard]) and the theme; no color literals live here.
 */
@Composable
fun HomeScreen() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        // Big Map card down the left — the primary glance and the tap-target into the Nav tab.
        HomeCard(modifier = Modifier.weight(1.6f).fillMaxHeight())

        // Right side: Vehicle across the top, then Weather + Media sharing an equal-height row.
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            HomeCard(modifier = Modifier.fillMaxWidth().weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(GAP),
            ) {
                HomeCard(modifier = Modifier.weight(1f).fillMaxHeight())
                HomeCard(modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** One empty placeholder tile in the app's glass-card style. Filled in by owner A later. */
@Composable
private fun HomeCard(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, shape = RoundedCornerShape(28.dp)) {
        // Intentionally empty — the layout only reserves the space.
    }
}

private val GAP = 22.dp
