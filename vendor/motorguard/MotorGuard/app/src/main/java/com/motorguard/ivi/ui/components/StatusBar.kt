package com.motorguard.ivi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top status bar. Right-aligned indicators: signal · wifi · bluetooth · avatar · date ·
 * clock (see docs/02-statusbar.md). Read-only for now — wire live telephony / Wi-Fi /
 * Bluetooth later. The avatar is a static glyph: there are no driver profiles to switch
 * between. Date + clock are live.
 */
@Composable
fun StatusBar(modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Indicator(Icons.Filled.SignalCellularAlt, "Signal", dim)
        Spacer(Modifier.width(16.dp))
        Indicator(Icons.Filled.Wifi, "Wi-Fi", dim)
        Spacer(Modifier.width(16.dp))
        Indicator(Icons.Filled.Bluetooth, "Bluetooth", dim)
        Spacer(Modifier.width(16.dp))
        Indicator(Icons.Filled.Person, "Driver", dim)

        Spacer(Modifier.width(20.dp))
        Text(
            text = liveDate(),
            fontSize = 15.sp,
            color = dim,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = liveClock(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Indicator(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = tint,
        modifier = Modifier.size(20.dp),
    )
}

/** Current date as "EEE, d MMM" (e.g. "Mon, 26 Jul"), refreshed each minute. */
@Composable
private fun liveDate(): String = liveFormatted("EEE, d MMM", 60_000)

/** Current time as HH:mm, refreshed every 10 s. */
@Composable
private fun liveClock(): String = liveFormatted("HH:mm", 10_000)

@Composable
private fun liveFormatted(pattern: String, everyMs: Long): String {
    val fmt = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    val value by produceState(initialValue = fmt.format(Date()), fmt, everyMs) {
        while (true) {
            this.value = fmt.format(Date())
            delay(everyMs)
        }
    }
    return value
}
