package com.motorguard.ivi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.data.vehicle.api.latestValueOrNull
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.diagnostics.VehicleData
import com.motorguard.ivi.ui.theme.MotorGuard
import kotlin.math.roundToInt

/**
 * The car, at a glance — charge, what it is doing, and anything left open.
 *
 * Replaces the named placeholder that stood here while Diagnostics was being built. Every
 * figure comes from the same [VehicleData] source that screen uses, so Home cannot disagree
 * with it.
 *
 * Nothing is invented. Each signal arrives wrapped in a SignalState precisely so "no
 * trustworthy value" is representable, and a signal that is Loading or Offline shows a dash
 * rather than a plausible-looking zero — a car reporting 0 km/h and 0% charge because the bus
 * is down is worse than one admitting it does not know.
 */
@Composable
fun VehicleCard(onOpenDiagnostics: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MotorGuard.colors

    val battery by VehicleData.source.battery.collectAsStateWithLifecycle()
    val metrics by VehicleData.source.metrics.collectAsStateWithLifecycle()
    val doors by VehicleData.source.doors.collectAsStateWithLifecycle()
    val tires by VehicleData.source.tires.collectAsStateWithLifecycle()

    val batteryData = battery.latestValueOrNull
    val metricsData = metrics.latestValueOrNull
    val doorsData = doors.latestValueOrNull

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        padding = PaddingValues(22.dp),
        soft = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onOpenDiagnostics),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Vehicle",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    if (batteryData?.charging == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text("Charging", fontSize = 12.sp, color = colors.accent)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Charge, given the room it deserves: it is the number a driver actually
                // looks for on a home screen.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = batteryData?.chargePercent?.roundToInt()?.toString() ?: "—",
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "%",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onBaseDim,
                        modifier = Modifier.padding(bottom = 7.dp, start = 2.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                ChargeBar(percent = batteryData?.chargePercent)

                Spacer(Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat(
                        icon = Icons.Filled.Speed,
                        value = metricsData?.speedKmh?.roundToInt()?.toString() ?: "—",
                        unit = "km/h",
                        modifier = Modifier.weight(1f),
                    )
                    Stat(
                        icon = Icons.Filled.Straighten,
                        value = metricsData?.odometerKm?.roundToInt()?.toString() ?: "—",
                        unit = "km",
                        modifier = Modifier.weight(1f),
                    )
                    Stat(
                        icon = Icons.Filled.Thermostat,
                        value = batteryData?.cellTempC?.roundToInt()?.toString() ?: "—",
                        unit = "°C",
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(18.dp))

                // Tyres, because the card had the room and this is the other thing an owner
                // checks before setting off. Four corners in wheel order, so the layout maps
                // onto the car rather than needing the labels read.
                Text(
                    text = "TYRES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = colors.onBaseDim,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val corners = listOf("FL", "FR", "RL", "RR")
                    corners.forEachIndexed { index, label ->
                        val psi = tires.getOrNull(index)?.latestValueOrNull?.psi
                        Tyre(label = label, psi = psi, modifier = Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.weight(1f))

                // Doors last, and only as a warning line. Everything shut is the normal case
                // and does not need a row of green ticks restating it.
                DoorLine(
                    anyOpen = doorsData?.anyOpen,
                    anyUnlocked = doorsData?.anyUnlocked,
                )
            }
        }
    }
}

@Composable
private fun ChargeBar(percent: Float?) {
    val colors = MotorGuard.colors
    val fraction = ((percent ?: 0f) / 100f).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.onBaseDim.copy(alpha = 0.18f)),
    ) {
        if (percent != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    // Low charge is the one state worth colouring differently; anything else
                    // is just the accent, so the card does not cry wolf.
                    .background(if (fraction <= 0.15f) MaterialTheme.colorScheme.error else colors.accent),
            )
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, value: String, unit: String, modifier: Modifier = Modifier) {
    val colors = MotorGuard.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.onBaseDim.copy(alpha = 0.08f))
            .padding(vertical = 10.dp, horizontal = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.onBaseDim, modifier = Modifier.size(15.dp))
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = " $unit",
                fontSize = 11.sp,
                color = colors.onBaseDim,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

/**
 * One corner. Under-inflation is the failure worth seeing from across the cabin, so it is the
 * only state that gets a colour; everything else stays quiet.
 */
@Composable
private fun Tyre(label: String, psi: Float?, modifier: Modifier = Modifier) {
    val colors = MotorGuard.colors
    val low = psi != null && psi < LOW_PSI
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (low) MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                else colors.onBaseDim.copy(alpha = 0.08f),
            )
            .padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, fontSize = 10.sp, color = colors.onBaseDim, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            text = psi?.let { "%.0f".format(it) } ?: "—",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Below this a tyre is flagged; the placards on most cars sit a little above it. */
private const val LOW_PSI = 30f

@Composable
private fun DoorLine(anyOpen: Boolean?, anyUnlocked: Boolean?) {
    val colors = MotorGuard.colors
    val (icon, text, tint) = when {
        anyOpen == null -> Triple(Icons.Filled.DoorFront, "Doors: no data", colors.onBaseDim)
        anyOpen -> Triple(Icons.Filled.DoorFront, "A door is open", MaterialTheme.colorScheme.error)
        anyUnlocked == true -> Triple(Icons.Filled.LockOpen, "Unlocked", colors.onBaseDim)
        else -> Triple(Icons.Filled.Lock, "Closed and locked", colors.onBaseDim)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(text = text, fontSize = 13.sp, color = tint)
    }
}
