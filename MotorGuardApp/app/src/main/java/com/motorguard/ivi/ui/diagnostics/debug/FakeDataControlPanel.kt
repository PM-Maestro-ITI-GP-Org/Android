package com.motorguard.ivi.ui.diagnostics.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motorguard.ivi.data.vehicle.api.DoorState
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.components.Pill
import com.motorguard.ivi.ui.diagnostics.ForcedCondition
import com.motorguard.ivi.ui.diagnostics.VehicleDebugControls
import com.motorguard.ivi.ui.diagnostics.render.Car3dTuning
import com.motorguard.ivi.ui.theme.SemanticColors

/**
 * Dev-only surface for driving [VehicleDebugControls] — force any hotspot into any severity,
 * flip individual door open/lock state, or fire several criticals at once to exercise Step 5's
 * alert-list ordering. Reachable by long-pressing the car stage; invisible otherwise. Depends on
 * [VehicleDebugControls] only — must not import anything from the fake-data-source module, so
 * this file compiles and works unchanged whichever [VehicleDebugControls] implementation
 * [com.motorguard.ivi.ui.diagnostics.VehicleData] hands it.
 *
 * Presented as an inline overlay, not a [androidx.compose.ui.window.Dialog]: a `Dialog` opens a
 * second Android window, and the car stage's `SurfaceView` is opaque with
 * `setZOrderOnTop(false)` — stacking a second window over it is exactly the layering risk Step 1
 * spent its budget avoiding (see `Car3dRenderer`'s class KDoc). An in-composition overlay has no
 * interaction with `SurfaceView` z-ordering at all.
 */
@Composable
fun FakeDataControlPanel(
    controls: VehicleDebugControls,
    livery: Car3dTuning.Livery,
    onLivery: (Car3dTuning.Livery) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val forced by controls.forced.collectAsStateWithLifecycle()
    val doors by controls.doorStates.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        GlassCard(
            padding = PaddingValues(0.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(880.dp)
                .fillMaxHeight()
                .padding(20.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Fake data controls",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(12.dp))
                    Pill(text = "Debug", bg = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        // Spec §9: fire several simultaneous alerts, to exercise the Step 5
                        // alert list's ordering in one tap instead of forcing three rows by hand.
                        controls.force(Hotspot.TIRE_FL, ForcedCondition.CRITICAL)
                        controls.force(Hotspot.BATTERY, ForcedCondition.CRITICAL)
                        controls.force(Hotspot.BRAKES, ForcedCondition.CRITICAL)
                    }) { Text("3 criticals") }
                    TextButton(onClick = { controls.resetAll() }) { Text("Reset all") }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close debug panel")
                    }
                }

                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(Hotspot.entries) { hotspot ->
                        ForcedRow(
                            hotspot = hotspot,
                            current = forced[hotspot] ?: ForcedCondition.AUTO,
                            onSelect = { controls.force(hotspot, it) },
                        )
                    }
                    item {
                        Text(
                            text = "Doors",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    items(doors, key = { it.door }) { doorState ->
                        DoorRow(
                            doorState = doorState,
                            onToggleOpen = { controls.setDoor(doorState.door, open = !doorState.open) },
                            onToggleLocked = { controls.setDoor(doorState.door, locked = !doorState.locked) },
                        )
                    }
                    item {
                        Text(
                            text = "Vehicle paint",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    item {
                        PaintRow("Body", livery.body) { onLivery(livery.copy(body = it)) }
                    }
                    item {
                        PaintRow("Rim", livery.rim) { onLivery(livery.copy(rim = it)) }
                    }
                    item {
                        PaintRow("Rim inner", livery.rimInner) { onLivery(livery.copy(rimInner = it)) }
                    }
                    item {
                        PaintRow("Brake disc", livery.disc) { onLivery(livery.copy(disc = it)) }
                    }
                    item {
                        PaintRow("Caliper", livery.caliper) { onLivery(livery.copy(caliper = it)) }
                    }
                }

                Text(
                    text = "Debug build only — not part of the driver UI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
            }
        }
    }
}

@Composable
private fun ForcedRow(
    hotspot: Hotspot,
    current: ForcedCondition,
    onSelect: (ForcedCondition) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = hotspot.label,
            modifier = Modifier.width(190.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForcedCondition.entries.forEach { condition ->
                // 76 dp touch-target exemption: this whole panel is developer chrome, never
                // shown to a driver, so the default (smaller) FilterChip height is fine here —
                // do not "fix" this to match the on-car hotspot dots' touch target.
                FilterChip(
                    selected = current == condition,
                    onClick = { onSelect(condition) },
                    label = { Text(condition.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = selectedColorFor(condition).copy(alpha = 0.30f),
                    ),
                )
            }
        }
    }
}

/**
 * The paint swatches for one part of the vehicle. Tapping one applies it to the live render
 * immediately — the whole point of putting this here rather than in a constant.
 *
 * Every colour is written as float components rather than a hex literal, because this file sits
 * under `ui/diagnostics/` where hex is banned. These are not theme colours and must not become
 * them: they are paint on a vehicle, and they have to look the same in day and night rather than
 * flipping with the theme.
 */
@Composable
private fun PaintRow(
    label: String,
    current: Color,
    onPick: (Color) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.width(190.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PAINT_SWATCHES.forEach { swatch ->
                val selected = swatch == current
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            },
                            shape = CircleShape,
                        )
                        .clickable { onPick(swatch) },
                )
            }
        }
    }
}

/**
 * The palette, ordered dark to light and then by hue: black, graphite, gunmetal, silver, white,
 * ice blue, deep blue, violet, red, yellow, green, rose.
 *
 * Deliberately a fixed set rather than a colour wheel. Every entry renders well on this model —
 * the values are lower than they look, because the clear coat is metallic and the environment
 * lifts them, and a free picker mostly produces colours that blow out.
 */
private val PAINT_SWATCHES = listOf(
    Color(red = 0.045f, green = 0.045f, blue = 0.05f),
    Color(red = 0.13f, green = 0.14f, blue = 0.17f),
    Color(red = 0.24f, green = 0.26f, blue = 0.30f),
    Color(red = 0.62f, green = 0.63f, blue = 0.65f),
    Color(red = 0.85f, green = 0.86f, blue = 0.88f),
    Color(red = 0.26f, green = 0.44f, blue = 0.52f),
    Color(red = 0.09f, green = 0.20f, blue = 0.38f),
    Color(red = 0.30f, green = 0.14f, blue = 0.52f),
    Color(red = 0.72f, green = 0.06f, blue = 0.06f),
    Color(red = 0.95f, green = 0.78f, blue = 0.05f),
    Color(red = 0.05f, green = 0.30f, blue = 0.12f),
    Color(red = 0.38f, green = 0.27f, blue = 0.29f),
)

@Composable
private fun DoorRow(
    doorState: DoorState,
    onToggleOpen: () -> Unit,
    onToggleLocked: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = doorState.door.label,
            modifier = Modifier.width(190.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = doorState.open,
                onClick = onToggleOpen,
                label = { Text("Open") },
            )
            FilterChip(
                selected = doorState.locked,
                onClick = onToggleLocked,
                label = { Text("Locked") },
            )
        }
    }
}

/** Selected-container colour per [ForcedCondition] — zero hex, only [SemanticColors] /
 *  [MaterialTheme.colorScheme]. Callers apply the 30% alpha. */
@Composable
private fun selectedColorFor(condition: ForcedCondition): Color = when (condition) {
    ForcedCondition.AUTO -> MaterialTheme.colorScheme.surfaceVariant
    ForcedCondition.OK -> SemanticColors.success
    ForcedCondition.CAUTION -> SemanticColors.caution
    ForcedCondition.CRITICAL -> SemanticColors.critical
    ForcedCondition.OFFLINE, ForcedCondition.STALE -> SemanticColors.offline
}
