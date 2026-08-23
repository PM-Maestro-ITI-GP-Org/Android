package com.motorguard.ivi.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.data.weather.WeatherRepository
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.theme.MotorGuard
import kotlin.math.roundToInt

/** Current conditions at ITI, matching the "Map · Vehicle · Weather + Media" layout this
 *  screen's own KDoc has always described — the one card of the three that was never built. */
@Composable
fun WeatherCard(modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { WeatherRepository.ensureStarted() }
    val weather by WeatherRepository.state.collectAsStateWithLifecycle()
    val failed by WeatherRepository.failed.collectAsStateWithLifecycle()
    val colors = MotorGuard.colors

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        padding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = iconFor(weather?.code),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = weather?.tempC?.let { "${it.roundToInt()}°C" } ?: "—",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = when {
                    weather != null -> weather!!.description
                    failed -> "Weather unavailable"
                    else -> "Loading weather…"
                },
                fontSize = 14.sp,
                color = colors.onBaseDim,
                maxLines = 1,
            )
        }
    }
}

private fun iconFor(code: Int?): ImageVector = when (code) {
    null -> Icons.Filled.Cloud
    0 -> Icons.Filled.WbSunny
    1, 2, 3, 45, 48 -> Icons.Filled.Cloud
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Filled.Grain
    71, 73, 75, 77, 85, 86 -> Icons.Filled.AcUnit
    95, 96, 99 -> Icons.Filled.Thunderstorm
    else -> Icons.Filled.Cloud
}
