package com.motorguard.ivi.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.fake.FakeVehicleDataSource
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.diagnostics.debug.FakeDataControlPanel
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Diagnostics screen (owner C — docs/05-diagnostics.md).
 *
 * Left (~58%): tappable car + hotspot dots ([CarScene]).
 * Right (~42%): health ring, live state card for the focused (or first-alerted)
 * hotspot, alert list. Debug button opens the FORCE-STATE panel.
 */
@Composable
fun DiagnosticsScreen(
    vm: DiagnosticsViewModel,
    modifier: Modifier = Modifier,
) {
    val focused by vm.focused.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val health by vm.healthScore.collectAsStateWithLifecycle()
    val showDebug by vm.showDebugPanel.collectAsStateWithLifecycle()

    // Idle: show the highest-severity live hotspot (top alert); focused: its card.
    val cardHotspot: Hotspot = focused
        ?: alerts.firstOrNull()?.hotspot
        ?: Hotspot.BATTERY

    Row(modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        // left pane — car
        GlassCard(Modifier.weight(1.38f).fillMaxHeight()) {
            Box(Modifier.fillMaxSize().padding(14.dp)) {
                CarScene(vm, Modifier.fillMaxSize())
            }
        }

        // right pane — telemetry column
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OverallHealthCard(score = health, alertCount = alerts.size)
            LiveCard(vm = vm, hotspot = cardHotspot, modifier = Modifier.fillMaxWidth())
            AlertsCard(vm = vm, alerts = alerts, modifier = Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "live · severity updates",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.showDebugPanel.value = !showDebug }) {
                    Text(if (showDebug) "Close debug" else "Force states")
                }
            }
        }

        // debug drawer (emits only when showDebug == true)
        if (showDebug) {
            FakeDataControlPanel(
                fake = vm.source as? FakeVehicleDataSource,
                onClose = { vm.showDebugPanel.value = false },
            )
        }
    }
}

@Composable
private fun OverallHealthCard(score: Int?, alertCount: Int) {
    GlassCard {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HealthRing(score = score, size = 76.dp)
            Spacer(Modifier.weight(1f))
            Column {
                Text("Overall health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val sub = when {
                    score == null -> "No telemetry yet"
                    alertCount == 0 -> "All systems OK"
                    alertCount == 1 -> "1 item needs attention"
                    else -> "$alertCount items need attention"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
    }
}

@Composable
private fun AlertsCard(
    vm: DiagnosticsViewModel,
    alerts: List<DiagnosticsViewModel.AlertRow>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text("Alerts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxSize()) {
                AlertList(
                    alerts = alerts,
                    onTapRow = { vm.focus(it.hotspot) },
                    onDismiss = { vm.dismiss(it.hotspot) },
                )
            }
        }
    }
}
