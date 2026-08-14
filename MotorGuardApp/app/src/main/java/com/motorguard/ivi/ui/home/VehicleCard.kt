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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.VehicleSeverityFlow
import com.motorguard.ivi.data.vehicle.api.latestValueOrNull
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.diagnostics.VehicleData
import com.motorguard.ivi.ui.diagnostics.render.rememberCar3dRenderer
import com.motorguard.ivi.ui.theme.MotorGuard
import com.motorguard.ivi.ui.theme.SemanticColors
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

    // The same severity derivation the Diagnostics tab renders, so Home cannot disagree with
    // it about what is wrong. It is a combine over flows this card already collects, not a
    // second source of truth.
    val severityFlow = remember { VehicleSeverityFlow(VehicleData.source) }
    val severities by remember(severityFlow) { severityFlow.severities }
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    // Worst first, so a critical fault is the one seen if the driver only glances once.
    val faults = remember(severities) {
        severities.entries
            .filter { it.value != null && it.value != Severity.OK }
            .sortedByDescending { it.value!!.ordinal }
            .map { it.key }
    }

    // With several faults, dwell on each in turn rather than picking one and hiding the rest.
    // Reset to the worst whenever the set changes, so a newly arrived critical is not queued
    // behind a caution the driver has already seen.
    var faultIndex by remember { mutableStateOf(0) }
    LaunchedEffect(faults) {
        faultIndex = 0
        if (faults.size > 1) {
            while (true) {
                kotlinx.coroutines.delay(FAULT_DWELL_MS)
                faultIndex = (faultIndex + 1) % faults.size
            }
        }
    }
    val focused = faults.getOrNull(faultIndex)

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

                // The car itself, the same Filament stage the Diagnostics tab renders.
                //
                // Safe to have here because tabs are replaced rather than hidden: opening
                // Diagnostics destroys this fragment, so there is only ever one engine and one
                // copy of the 15 MB model alive. It is given a fixed slice of the card rather
                // than a weight so the figures below keep their room on a short card.
                //
                // No hotspot focus and no cutaway: this is the car at a glance, and the tap
                // goes to Diagnostics where those controls actually exist.
                val car = rememberCar3dRenderer(stageColor = MaterialTheme.colorScheme.surface)

                // Paint every component by its severity, not just the faulted ones: the green
                // of a healthy pack is what makes an amber one mean something.
                Hotspot.entries.forEach { hotspot ->
                    val tint = SemanticColors.forSeverity(severities[hotspot])
                    SideEffect { car.setComponentColor(hotspot, tint) }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    // Passing the faulted hotspot as the focus is all the zoom takes: the
                    // renderer already frames a component and animates the move, which is how
                    // the Diagnostics tab behaves when one is tapped.
                    car.Render(
                        focus = focused,
                        onBackgroundTap = onOpenDiagnostics,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Say which part is being shown. Zooming into a wheel arch is meaningless
                    // on its own — from the outside a framed brake and a framed tyre look much
                    // the same, and the driver should not have to work out which they are
                    // looking at.
                    focused?.let { hotspot ->
                        FaultChip(
                            hotspot = hotspot,
                            severity = severities[hotspot],
                            position = if (faults.size > 1) "${faultIndex + 1}/${faults.size}" else null,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

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

                Spacer(Modifier.height(14.dp))

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

/**
 * Names the part currently framed, and how bad it is.
 *
 * Sits over the stage rather than under it so it moves with the car and cannot be mistaken for
 * a caption belonging to the figures below. Carries the position in the rotation when there is
 * more than one fault, so a driver can tell "there are three of these" from a glance rather
 * than by watching it cycle.
 */
@Composable
private fun FaultChip(
    hotspot: Hotspot,
    severity: Severity?,
    position: String?,
    modifier: Modifier = Modifier,
) {
    val tint = SemanticColors.forSeverity(severity)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = hotspot.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
        if (position != null) {
            Spacer(Modifier.width(7.dp))
            Text(text = position, fontSize = 11.sp, color = Color.White.copy(alpha = 0.65f))
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

/** How long each fault is framed before moving to the next. */
private const val FAULT_DWELL_MS = 4_000L
