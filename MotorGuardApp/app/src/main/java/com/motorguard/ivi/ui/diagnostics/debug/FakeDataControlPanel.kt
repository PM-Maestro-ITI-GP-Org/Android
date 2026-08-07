package com.motorguard.ivi.ui.diagnostics.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.fake.FakeVehicleDataSource

/**
 * Dev-only panel to force any hotspot into OK / CAUTION / CRITICAL / OFFLINE /
 * STALE, per the phase-1 debugging requirement (README-phase1-ui-brief §9).
 * "Reset all" clears every forced state. This UI is NOT part of the shipping
 * surface and lives behind the Diagnostics screen's "Force states" toggle.
 */
@Composable
fun FakeDataControlPanel(
    fake: FakeVehicleDataSource?,
    onClose: () -> Unit,
) {
    if (fake == null) {
        DebugPanelCard(onClose) {
            Text(
                "Debug panel unavailable: VehicleDataSource is not the fake source " +
                    "(active impl: ${"<real>"}).",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    val forced by fake.forcedSnapshotFlow.collectAsState()

    DebugPanelCard(onClose) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Hotspot.entries.forEach { hotspot ->
                item(key = hotspot.name) {
                    Column {
                        Text(
                            hotspot.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.padding(top = 4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FakeVehicleDataSource.ForcedState.entries.forEach { st ->
                                FilterChip(
                                    selected = forced[hotspot] == st,
                                    onClick = { fake.setForced(hotspot, st) },
                                    label = { Text(st.name, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    TextButton(onClick = { fake.resetAll() }) { Text("Reset all to AUTO") }
                }
            }
        }
    }
}

@Composable
private fun DebugPanelCard(onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .width(380.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DEBUG · force vehicle state",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("Close") }
        }
        content()
    }
}
