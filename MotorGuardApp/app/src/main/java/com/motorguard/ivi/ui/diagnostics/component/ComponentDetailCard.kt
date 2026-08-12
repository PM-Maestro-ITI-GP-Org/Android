package com.motorguard.ivi.ui.diagnostics.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.data.vehicle.api.DoorState
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.components.Pill
import com.motorguard.ivi.ui.diagnostics.FocusedTelemetry
import com.motorguard.ivi.ui.theme.SemanticColors
import kotlinx.coroutines.delay

/**
 * The "Component detail" slot: a live telemetry card for the focused component, or a deliberate
 * empty state when nothing is focused.
 *
 * [telemetry] is a lambda rather than a value so the snapshot read happens inside THIS composable.
 * Passing the value would make every telemetry tick invalidate the whole screen — including the car
 * stage and the Filament surface underneath it.
 */
@Composable
internal fun ComponentDetailPanel(
    telemetry: () -> FocusedTelemetry?,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = telemetry(),
        // Keyed on the hotspot, not the whole state. Without this, every telemetry tick would be a
        // new target and the card would re-slide once a second. With it, a tick recomposes the
        // current content in place and only an actual component change animates.
        contentKey = { it?.hotspot },
        transitionSpec = {
            (
                slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 3 } +
                    fadeIn(tween(180))
                ).togetherWith(
                slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 3 } +
                    fadeOut(tween(140)),
            ).using(SizeTransform(clip = false))
        },
        modifier = modifier,
        label = "componentDetail",
    ) { target ->
        // AnimatedContent keeps the OUTGOING entry composing against its own captured target, so
        // the departing card keeps its numbers for the whole slide-out instead of blanking the
        // instant focus clears. That is why this targets the telemetry and not ui.focusedHotspot.
        if (target == null) {
            EmptyDetailPanel(Modifier.fillMaxSize())
        } else {
            DetailCard(target, Modifier.fillMaxSize())
        }
    }
}

/**
 * Shown when nothing is focused. The copy describes the interface, never the vehicle: "all systems
 * nominal" would be a claim about the car, and this panel has no data to support one.
 */
@Composable
private fun EmptyDetailPanel(modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Text(
                text = "Component detail",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Select a component on the car to inspect it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DetailCard(telemetry: FocusedTelemetry, modifier: Modifier = Modifier) {
    val overall = telemetry.overallSeverity()

    GlassCard(modifier) {
        Row(Modifier.fillMaxSize()) {
            // Severity earns its colour here and mostly nowhere else: a full-height edge bar that
            // is simply absent when the component is fine, so amber or red on this card always
            // means something rather than being decoration.
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        if (overall != null && overall != Severity.OK) {
                            SemanticColors.forSeverity(overall)
                        } else {
                            Color.Transparent
                        },
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
            ) {
                DetailHeader(telemetry, overall)
                Spacer(Modifier.height(20.dp))
                DetailContent(telemetry)
            }
        }
    }
}

@Composable
private fun DetailHeader(telemetry: FocusedTelemetry, overall: Severity?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = telemetry.hotspot.label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))

        if (overall != null) {
            Pill(
                text = when (overall) {
                    Severity.OK -> "OK"
                    Severity.CAUTION -> "Caution"
                    Severity.CRITICAL -> "Critical"
                },
                bg = SemanticColors.forSeverity(overall),
            )
            Spacer(Modifier.width(10.dp))
        }

        // Freshness is called out only when it is NOT live — live is the unmarked default, so a
        // badge on this card always means "trust this less".
        when (val signal = telemetry.signal()) {
            is SignalState.Stale -> StaleBadge(signal.lastTimestampMs)
            SignalState.Offline -> Pill(text = "No data", bg = SemanticColors.offline)
            SignalState.Loading -> Pill(text = "Connecting", bg = SemanticColors.offline)
            is SignalState.Live -> Unit
        }
    }
}

@Composable
private fun DetailContent(telemetry: FocusedTelemetry) {
    when (telemetry) {
        is FocusedTelemetry.Battery -> DetailBody(telemetry.signal, skeletonSlots = 4) { r, stale ->
            HeroValue(
                value = TelemetryFormat.percent(r.data.chargePercent),
                caption = "Charge",
                severity = r.charge,
                stale = stale,
            )
            Spacer(Modifier.height(22.dp))
            MetricRow {
                MetricCell("Cell temp", TelemetryFormat.tempC(r.data.cellTempC), r.cellTemp, Modifier.weight(1f))
                MetricCell("Health", TelemetryFormat.percent(r.data.healthPercent), r.health, Modifier.weight(1f))
                MetricCell("Cycles", TelemetryFormat.count(r.data.cycleCount), null, Modifier.weight(1f))
                MetricCell("Charging", TelemetryFormat.charging(r.data.charging), null, Modifier.weight(1f))
            }
        }

        is FocusedTelemetry.Tire -> DetailBody(telemetry.signal, skeletonSlots = 2) { r, stale ->
            HeroValue(
                value = TelemetryFormat.psi(r.data.psi),
                caption = "PSI",
                severity = r.psi,
                stale = stale,
            )
            Spacer(Modifier.height(22.dp))
            MetricRow {
                MetricCell("Temp", TelemetryFormat.tempC(r.data.tempC), r.temp, Modifier.weight(1f))
            }
        }

        is FocusedTelemetry.Motor -> DetailBody(telemetry.signal, skeletonSlots = 2) { r, stale ->
            HeroValue(
                value = TelemetryFormat.percent(r.data.loadPercent),
                caption = "Load",
                severity = r.load,
                stale = stale,
            )
            Spacer(Modifier.height(22.dp))
            MetricRow {
                MetricCell("Temp", TelemetryFormat.tempC(r.data.tempC), r.temp, Modifier.weight(1f))
                MetricCell("RPM", TelemetryFormat.rpm(r.data.rpm), null, Modifier.weight(1f))
            }
        }

        is FocusedTelemetry.Brakes -> DetailBody(telemetry.signal, skeletonSlots = 2) { r, stale ->
            HeroValue(
                value = TelemetryFormat.percent(r.data.padWearPercent),
                caption = "Pad wear",
                severity = r.wear,
                stale = stale,
            )
            Spacer(Modifier.height(22.dp))
            MetricRow {
                MetricCell("Fluid", TelemetryFormat.fluid(r.data.fluidOk), r.fluid, Modifier.weight(1f))
            }
        }

        is FocusedTelemetry.Doors -> DetailBody(telemetry.signal, skeletonSlots = 4) { r, stale ->
            val openCount = r.data.doors.count { it.open }
            HeroValue(
                value = if (openCount == 0) "All closed" else "$openCount open",
                caption = "Doors",
                severity = r.overall,
                stale = stale,
            )
            Spacer(Modifier.height(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                r.data.doors.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { door -> DoorChip(door, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * The four-state rule from the phase-1 brief §8, written exactly once.
 *
 * The important branch is `Offline`: it does not invoke [content] at all. No metric cell is
 * composed, so there is no label with a dash where a number used to be and no greyed-out last
 * reading — the card structurally cannot show a number it does not have.
 */
@Composable
private fun <R> DetailBody(
    signal: SignalState<R>,
    skeletonSlots: Int,
    content: @Composable (reading: R, stale: Boolean) -> Unit,
) {
    when (signal) {
        is SignalState.Live -> Column { content(signal.data, false) }

        // Last known values, visibly dimmed. The age and the explicit "Stale" wording live in the
        // header badge, so the reader is told both that it is old and exactly how old.
        is SignalState.Stale -> Column(Modifier.graphicsLayer { alpha = 0.55f }) {
            content(signal.lastData, true)
        }

        SignalState.Offline -> NoDataBody()
        SignalState.Loading -> SkeletonBody(skeletonSlots)
    }
}

@Composable
private fun NoDataBody() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No data",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = SemanticColors.offline,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Signal offline",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
        )
    }
}

/** Static, not shimmering: this state lasts about one tick, and an animation would draw the eye to
 *  the one moment on this screen that carries no information. */
@Composable
private fun SkeletonBody(slots: Int) {
    val block = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column {
        Box(
            Modifier
                .height(56.dp)
                .width(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(block),
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(slots) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(block),
                )
            }
        }
    }
}

@Composable
private fun HeroValue(value: String, caption: String, severity: Severity?, stale: Boolean) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            // See [MetricCell] for why every text in this card is pinned to one line.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = if (severity != null && severity != Severity.OK) {
                SemanticColors.forSeverity(severity)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = if (stale) "$caption · last known" else caption,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
        )
    }
}

@Composable
private fun MetricRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

/**
 * A single labelled value. [severity] of `null` means "the domain defines no rule for this field"
 * — it renders in the normal text colour, NOT the offline grey, because the value is perfectly
 * live and trustworthy; it simply has nothing to be good or bad against.
 *
 * **Every text here is pinned to one line, and that is load-bearing rather than cosmetic.** These
 * cells share a row by weight, so each is a fraction of a panel that is itself a fraction of the
 * screen. Let a value wrap and its row grows, which pushes everything below it down and pulls it
 * back up on the next tick that happens to be a character shorter. Measured case: "Not charging"
 * broke across three lines in a quarter-width cell and collapsed to one the moment charging
 * started.
 *
 * One line makes the card's height a function of its STRUCTURE, never of its values, which is the
 * only version of this that is stable against data nobody has seen yet.
 */
@Composable
private fun MetricCell(
    label: String,
    value: String,
    severity: Severity?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            // softWrap = false as well as maxLines: maxLines alone still lets the layout break the
            // line and then clip it, so a value could ellipsise while a shorter one did not.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = if (severity != null && severity != Severity.OK) {
                SemanticColors.forSeverity(severity)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun DoorChip(state: DoorState, modifier: Modifier = Modifier) {
    val tint = when {
        state.open -> SemanticColors.critical
        !state.locked -> SemanticColors.caution
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(modifier) {
        Text(
            text = state.door.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(text = TelemetryFormat.doorOpen(state.open), bg = tint)
            Pill(
                text = TelemetryFormat.doorLock(state.locked),
                bg = if (state.locked) MaterialTheme.colorScheme.onSurface else SemanticColors.caution,
            )
        }
    }
}

/**
 * The explicit staleness indicator plus a live-updating age (brief §8).
 *
 * Its own composable so the one-second tick invalidates a single [Pill] rather than the whole card.
 * [System.currentTimeMillis] matches the clock the data source stamps with — elapsed-realtime would
 * produce a nonsensical age. Re-assigning an identical string is a no-op under snapshot structural
 * equality, so in the minutes range 59 of every 60 ticks schedule nothing at all.
 */
@Composable
private fun StaleBadge(lastTimestampMs: Long, modifier: Modifier = Modifier) {
    var label by remember(lastTimestampMs) {
        mutableStateOf(TelemetryFormat.age(System.currentTimeMillis() - lastTimestampMs))
    }
    LaunchedEffect(lastTimestampMs) {
        while (true) {
            label = TelemetryFormat.age(System.currentTimeMillis() - lastTimestampMs)
            delay(1_000L)
        }
    }
    Pill(text = "Stale · $label", bg = SemanticColors.caution, modifier = modifier)
}

/** Worst-of across the reading's graded fields, or null when there is no reading to grade. */
private fun FocusedTelemetry.overallSeverity(): Severity? = when (this) {
    is FocusedTelemetry.Battery -> signal.readingOrNull()?.overall
    is FocusedTelemetry.Motor -> signal.readingOrNull()?.overall
    is FocusedTelemetry.Brakes -> signal.readingOrNull()?.overall
    is FocusedTelemetry.Doors -> signal.readingOrNull()?.overall
    is FocusedTelemetry.Tire -> signal.readingOrNull()?.overall
}

private fun FocusedTelemetry.signal(): SignalState<*> = when (this) {
    is FocusedTelemetry.Battery -> signal
    is FocusedTelemetry.Motor -> signal
    is FocusedTelemetry.Brakes -> signal
    is FocusedTelemetry.Doors -> signal
    is FocusedTelemetry.Tire -> signal
}

private fun <T> SignalState<T>.readingOrNull(): T? = when (this) {
    is SignalState.Live -> data
    is SignalState.Stale -> lastData
    else -> null
}
