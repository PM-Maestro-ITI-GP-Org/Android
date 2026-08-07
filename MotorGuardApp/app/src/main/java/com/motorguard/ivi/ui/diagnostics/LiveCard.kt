package com.motorguard.ivi.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.data.vehicle.api.Door
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import com.motorguard.ivi.data.vehicle.api.isOfflineOrLoading
import com.motorguard.ivi.data.vehicle.api.latestValueOrNull
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.components.Pill
import com.motorguard.ivi.ui.theme.SemanticColors

/**
 * Live state card for the currently focused hotspot (or the first alert in idle).
 *
 * Card header uses the signal's live severity color; the body shows the hotspot's
 * telemetry fields (per docs/05-diagnostics.md §"Hotspot behavior"). Offline
 * renders "No data" + a grey pill — never a fabricated number.
 */
@Composable
fun LiveCard(
    vm: DiagnosticsViewModel,
    hotspot: Hotspot,
    modifier: Modifier = Modifier,
) {
    val severities by vm.severities.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val motor by vm.motor.collectAsStateWithLifecycle()
    val brakes by vm.brakes.collectAsStateWithLifecycle()
    val tires by vm.tires.collectAsStateWithLifecycle()
    val doors by vm.doors.collectAsStateWithLifecycle()

    GlassCard(modifier = modifier) {
        Column(Modifier.padding(18.dp)) {
            when (hotspot) {
                Hotspot.BATTERY -> BatteryContent(battery, severities[hotspot])
                Hotspot.MOTOR -> MotorContent(motor, severities[hotspot])
                Hotspot.BRAKES -> BrakesContent(brakes, severities[hotspot])
                Hotspot.DOORS -> DoorsContent(
                    doors,
                    severities[hotspot],
                    onToggleDoor = { d -> vm.toggleDoor(d) },
                )
                Hotspot.TIRE_FL, Hotspot.TIRE_FR, Hotspot.TIRE_RL, Hotspot.TIRE_RR -> {
                    val idx = Hotspot.tireCorners.indexOf(hotspot)
                    TireContent(
                        state = tires.getOrElse(idx) { SignalState.Offline },
                        severity = severities[hotspot],
                        tireLabel = hotspot.label,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// header bits shared by every card

@Composable
private fun CardTitle(
    title: String,
    stateLabel: String,
    stateColor: Color?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Pill(text = stateLabel, bg = stateColor ?: MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(6.dp))
}

// ---------------------------------------------------------------------------
// per-hotspot bodies

@Composable
private fun BatteryContent(
    signal: SignalState<com.motorguard.ivi.data.vehicle.api.BatteryTelemetry>,
    severity: Severity?,
) {
    val b = signal.latestValueOrNull
    CardTitle(
        title = "HV Battery",
        stateLabel = if (b == null) "No data" else (severity?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—"),
        stateColor = if (b == null) null else SemanticColors.forSeverity(severity),
    )
    Spacer(Modifier.height(12.dp))
    if (b == null) NoDataBody() else {
        StatRow("State of charge", "${b.chargePercent.toInt()}%")
        StatRow("Cell temperature", "${b.cellTempC.toInt()} °C")
        StatRow("Health", "${b.healthPercent.toInt()}%")
        StatRow("Cycles", "${b.cycleCount}")
        StatRow("Charging", if (b.charging) "Active" else "Idle")
    }
}

@Composable
private fun MotorContent(
    signal: SignalState<com.motorguard.ivi.data.vehicle.api.MotorTelemetry>,
    severity: Severity?,
) {
    val m = signal.latestValueOrNull
    CardTitle("Drive Motor",
        stateLabel = if (m == null) "No data" else severity?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—",
        stateColor = if (m == null) null else SemanticColors.forSeverity(severity),
    )
    Spacer(Modifier.height(12.dp))
    if (m == null) NoDataBody() else {
        StatRow("Load", "${m.loadPercent.toInt()}%")
        StatRow("Temperature", "${m.tempC.toInt()} °C")
        StatRow("RPM", "${m.rpm}")
    }
}

@Composable
private fun BrakesContent(
    signal: SignalState<com.motorguard.ivi.data.vehicle.api.BrakeTelemetry>,
    severity: Severity?,
) {
    val br = signal.latestValueOrNull
    CardTitle("Brakes",
        stateLabel = if (br == null) "No data" else severity?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—",
        stateColor = if (br == null) null else SemanticColors.forSeverity(severity),
    )
    Spacer(Modifier.height(12.dp))
    if (br == null) NoDataBody() else {
        StatRow("Pad wear", "${br.padWearPercent.toInt()}%")
        StatRow("Fluid", if (br.fluidOk) "OK" else "LOW")
    }
}

@Composable
private fun DoorsContent(
    signal: SignalState<com.motorguard.ivi.data.vehicle.api.DoorsTelemetry>,
    severity: Severity?,
    onToggleDoor: (Door) -> Unit,
) {
    val d = signal.latestValueOrNull
    CardTitle("Doors",
        stateLabel = if (d == null) "No data" else severity?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—",
        stateColor = if (d == null) null else SemanticColors.forSeverity(severity),
    )
    Spacer(Modifier.height(12.dp))
    if (d == null) NoDataBody() else {
        d.doors.forEach { state ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.door.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${if (state.open) "Open" else "Closed"} · ${if (state.locked) "Locked" else "Unlocked"}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = if (state.open) SemanticColors.critical else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                // Debug affordance: tap a door row to toggle open/closed (fake data).
                androidx.compose.material3.TextButton(onClick = { onToggleDoor(state.door) }) {
                    Text(if (state.open) "Close" else "Open", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TireContent(
    state: SignalState<com.motorguard.ivi.data.vehicle.api.TireTelemetry>,
    severity: Severity?,
    tireLabel: String,
) {
    val t = state.latestValueOrNull
    CardTitle(tireLabel,
        stateLabel = if (t == null) "No data" else severity?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—",
        stateColor = if (t == null) null else SemanticColors.forSeverity(severity),
    )
    Spacer(Modifier.height(12.dp))
    if (t == null) NoDataBody() else {
        StatRow("Pressure", "${t.psi} PSI")
        StatRow("Temperature", "${t.tempC.toInt()} °C")
    }
}

@Composable
private fun NoDataBody() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "No data",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
