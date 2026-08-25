package com.motorguard.ivi.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.maxmaster.materialmascot.config.MascotConfig
import com.maxmaster.materialmascot.engine.BotState
import com.maxmaster.materialmascot.ui.MaterialBot
import com.motorguard.ivi.MainActivity.Tab
import com.motorguard.ivi.data.UserActivity
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.VehicleSeverityFlow
import com.motorguard.ivi.ui.diagnostics.VehicleData
import com.motorguard.ivi.ui.theme.MotorGuard
import com.motorguard.ivi.ui.theme.Tokens
import com.motorguard.ivi.ui.voice.VoiceOverlayState

// The rail reads dark even in light mode, like the reference. Its background now comes from the
// theme so it picks up the album hue with the rest of the app; the dim foreground stays pinned.
private val RailActiveBg = Color.White.copy(alpha = 0.10f)
private val RailDim = Tokens.Night.onBaseDim

private data class RailItem(
    val tab: Tab,
    val label: String,
    val on: ImageVector,
    val off: ImageVector,
)

private val items = listOf(
    RailItem(Tab.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    RailItem(Tab.MEDIA, "Media", Icons.Filled.MusicNote, Icons.Outlined.MusicNote),
    RailItem(Tab.VIDEO, "Videos", Icons.Filled.Movie, Icons.Outlined.Movie),
    RailItem(Tab.NAV, "Navigation", Icons.Filled.Navigation, Icons.Outlined.Navigation),
    RailItem(Tab.PHONE, "Phone", Icons.Filled.Phone, Icons.Outlined.Phone),
    RailItem(Tab.DIAGNOSTICS, "Diagnostics", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar),
    RailItem(Tab.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * Fixed left rail. One selected item at a time (rounded highlight), a voice/mic button,
 * and the MOTOR GUARD wordmark pinned at the bottom. See docs/01-navrail.md.
 *
 * Seven buttons is the ceiling at 56 dp + 16 dp gaps: the column still clears the
 * wordmark on the 1024×600 reflow. An eighth tab needs a scrollable rail.
 */
@Composable
fun NavRail(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    onVoice: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(92.dp)
            .background(MotorGuard.colors.railBg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        items.forEach { item ->
            RailButton(
                icon = if (item.tab == selected) item.on else item.off,
                label = item.label,
                selected = item.tab == selected,
                onClick = { onSelect(item.tab) },
            )
        }
        // Voice: not a tab — triggers the assistant overlay.
        RailButton(
            icon = Icons.Filled.Mic,
            label = "Voice",
            selected = false,
            onClick = onVoice,
        )

        Spacer(Modifier.weight(1f))
        BrandMark()
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .size(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) RailActiveBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MotorGuard.colors.accent else RailDim,
            modifier = Modifier.size(30.dp),
        )
    }
}

/**
 * VEGA's docked home: calm when the car is, alert the moment a fault is — the same
 * CAUTION/CRITICAL check [com.motorguard.ivi.MainActivity.autoOpenDiagnosticsOnFault] uses, so
 * the mascot never disagrees with what actually opens Diagnostics.
 *
 * Normally fades out while the voice overlay is open and reports its own screen position on
 * every layout pass, so [com.motorguard.ivi.ui.voice.VoiceOverlayUi]'s flight — a separate
 * window, not a child of this composition — knows where to start from. See [VoiceOverlayState].
 *
 * A fault overrides that flight. Flying to centre screen is right for an ordinary "hey VEGA" —
 * the driver's attention is already on the overlay they just opened — but a fault is the one
 * time VEGA has bad news to deliver *from its own corner*: staying anchored where the driver's
 * eye already knows to check reads as "look here" in a way relocating to the middle of the
 * screen would undercut. [BotState.Confused] gives it the reaction for this — a genuinely
 * uneasy-looking face, not a status icon standing in for one — and it holds that state (small,
 * in its usual spot) for as long as the fault is live, voice or not: a fault is not something to
 * stop pointing out just because the driver isn't currently talking to VEGA. Opening the overlay
 * on top of that fault grows it, the
 * one deliberate size change here, for exactly the window VEGA has the driver's attention on the
 * subject — closing the overlay shrinks it back to its normal size without touching the "!",
 * which only clears once the motor genuinely is normal again.
 */
@Composable
private fun BrandMark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val severityFlow = remember { VehicleSeverityFlow(VehicleData.source) }
        val severities by remember(severityFlow) { severityFlow.severities }
            .collectAsStateWithLifecycle(initialValue = emptyMap())
        val hasFault = severities.values.any { it == Severity.CAUTION || it == Severity.CRITICAL }

        val overlayOpen by VoiceOverlayState.isOpen.collectAsStateWithLifecycle()
        val dockAlpha by animateFloatAsState(
            targetValue = if (overlayOpen && !hasFault) 0f else 1f,
            label = "mascot-dock-fade",
        )
        val dockSize by animateDpAsState(
            targetValue = if (overlayOpen && hasFault) DOCKED_MASCOT_ALERT_SIZE else DOCKED_MASCOT_SIZE,
            animationSpec = spring(dampingRatio = 0.6f),
            label = "mascot-dock-size",
        )

        // Ticks once a second purely so this recomposes and re-checks the clock — nothing else
        // would ever tell it time has passed while the driver simply isn't touching anything.
        val lastInteraction by UserActivity.lastInteractionMs.collectAsStateWithLifecycle()
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000L)
                now = SystemClock.elapsedRealtime()
            }
        }
        val isDozing = now - lastInteraction >= SLEEP_AFTER_MS

        val view = LocalView.current
        Box(
            modifier = Modifier
                .size(dockSize)
                .graphicsLayer { alpha = dockAlpha }
                .onGloballyPositioned { coords ->
                    val screenOrigin = IntArray(2).also { view.getLocationOnScreen(it) }
                    val local = coords.positionInRoot()
                    VoiceOverlayState.dockedScreenPosition =
                        Offset(screenOrigin[0] + local.x, screenOrigin[1] + local.y)
                },
        ) {
            MaterialBot(
                config = MascotConfig(
                    // A fault always wins over dozing off — that is the one time the car most
                    // needs the driver's attention, not less of it.
                    //
                    // Confused, not Alert or Exclaim: Alert keeps Idle's face and only makes the
                    // eyes a little rounder plus a barely-visible 2.5 Hz jitter, which reads as
                    // "no change" at the rail's small size. Exclaim reads unambiguously but
                    // replaces the face outright with a literal "!" glyph, which isn't a
                    // reaction, it's a sign. Confused keeps a face -- mismatched tilted eyes and
                    // a tilted head, no symbol -- so it reads as VEGA looking uneasy about
                    // something rather than displaying an icon.
                    state = when {
                        hasFault -> BotState.Confused
                        isDozing -> BotState.Sleepy
                        else -> BotState.Idle
                    },
                    color = MotorGuard.colors.accent,
                    size = dockSize,
                ),
                contentDescription = "Motor Guard",
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "MOTOR\nGUARD",
            color = RailDim,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            lineHeight = 11.sp,
        )
    }
}

/** How long nothing on screen has to go untouched before the docked mascot dozes off. */
private const val SLEEP_AFTER_MS = 20_000L

/** MaterialBot's ball is drawn at roughly 1.27x this box, uncropped by design — sized up from
 *  the rail's other 30 dp glyphs so the mascot actually reads as the rail's focal point. */
private val DOCKED_MASCOT_SIZE = 46.dp

/** What it grows to for the duration the voice overlay is open on top of an active fault (see
 *  [BrandMark]'s KDoc). 92.dp is the rail's own width — this stays comfortably inside it
 *  (~8.dp clear on each side) rather than crowding or clipping against the rail edge. */
private val DOCKED_MASCOT_ALERT_SIZE = 76.dp