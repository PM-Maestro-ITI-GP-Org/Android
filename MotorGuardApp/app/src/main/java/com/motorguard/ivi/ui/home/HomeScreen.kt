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
 * The map and now-playing cards are live — each reads the same session its own tab drives, so
 * what Home shows is the real trip and the real player rather than a copy. The vehicle card is
 * vehicle card reads the same telemetry the Diagnostics tab does, so Home cannot disagree
 * with it.
 */
@Composable
fun HomeScreen(
    onOpenMedia: () -> Unit,
    onOpenNav: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        NavCard(
            onOpenNav = onOpenNav,
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
            VehicleCard(
                onOpenDiagnostics = onOpenDiagnostics,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            NowPlayingCard(
                onOpenMedia = onOpenMedia,
                modifier = Modifier.fillMaxWidth(),
            )

            // Notifications have nowhere else to go: this app is the launcher and hides the
            // system bars, so CarSystemUI's heads-up and Notification Center are both covered.
            NotificationBanner()
        }
    }
}
