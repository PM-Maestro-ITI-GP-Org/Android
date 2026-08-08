package com.motorguard.ivi.ui.diagnostics.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.diagnostics.VehicleAlert
import com.motorguard.ivi.ui.theme.SemanticColors

/** Automotive touch minimum, mirroring `res/values/dimens.xml`'s `touch_min`. */
private val TouchTarget = 76.dp

/**
 * Severity alerts, critical first.
 *
 * This list is also the only way to reach a component on the hidden side of the car: far-side
 * hotspot dots stop accepting taps once occluded, precisely so they cannot steal a tap aimed at the
 * near-side dot beside them. Tapping a row focuses that component, which swings the camera round to
 * it — so an alert is always actionable even when its dot is not.
 */
@Composable
internal fun AlertList(
    alerts: List<VehicleAlert>,
    onAlertTap: (Hotspot) -> Unit,
    onAlertDismiss: (Hotspot) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Alerts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                if (alerts.isNotEmpty()) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(SemanticColors.forSeverity(alerts.first().severity))
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = alerts.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.surface,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (alerts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        // A statement about the alert list, not about the car: the list being
                        // empty is a fact we have; "the vehicle is fine" is a claim we do not.
                        text = "No active alerts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(alerts, key = { it.hotspot }) { alert ->
                        AlertRow(
                            alert = alert,
                            onTap = { onAlertTap(alert.hotspot) },
                            onDismiss = { onAlertDismiss(alert.hotspot) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(alert: VehicleAlert, onTap: () -> Unit, onDismiss: () -> Unit) {
    val tint = SemanticColors.forSeverity(alert.severity)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClickLabel = "Show ${alert.hotspot.label}") { onTap() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = alert.hotspot.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append(
                        when (alert.severity) {
                            Severity.CRITICAL -> "Critical"
                            Severity.CAUTION -> "Caution"
                            Severity.OK -> "OK" // unreachable: OK never becomes an alert
                        },
                    )
                    if (alert.since > 0L) {
                        append(" · ")
                        append(TelemetryFormat.age(System.currentTimeMillis() - alert.since))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
            )
        }

        // Dismiss acknowledges the row only. It does not touch severity, the dot colour or the
        // health ring — the car stays honest whatever the driver chooses to silence.
        Box(
            Modifier
                .size(TouchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Dismiss ${alert.hotspot.label} alert") {
                    onDismiss()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null, // the clickable above already carries the label
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
