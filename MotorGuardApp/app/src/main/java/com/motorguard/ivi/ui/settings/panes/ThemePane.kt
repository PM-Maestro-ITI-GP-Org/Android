package com.motorguard.ivi.ui.settings.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.components.MgSwitch
import com.motorguard.ivi.ui.components.SectionCard
import com.motorguard.ivi.ui.components.SettingRow
import com.motorguard.ivi.ui.theme.ThemeMode
import com.motorguard.ivi.ui.theme.ThemeState
import kotlin.math.roundToInt

@Composable
fun ThemePane() {
    val isAuto = ThemeState.mode == ThemeMode.AUTO
    val systemDark = isSystemInDarkTheme()
    // Which mode is actually showing (matters when Auto is on).
    val effectiveDark = when (ThemeState.mode) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.AUTO -> systemDark
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        SectionCard(title = "Appearance") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeCard(
                    label = "Day",
                    icon = Icons.Filled.LightMode,
                    selected = !effectiveDark,
                    // When Auto is on, cards become read-only indicators.
                    enabled = !isAuto,
                    onClick = { ThemeState.mode = ThemeMode.DAY },
                    modifier = Modifier.weight(1f),
                )
                ModeCard(
                    label = "Night",
                    icon = Icons.Filled.DarkMode,
                    selected = effectiveDark,
                    enabled = !isAuto,
                    onClick = { ThemeState.mode = ThemeMode.NIGHT },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionCard {
            SettingRow(
                title = "Auto day / night",
                subtitle = "Follow the light sensor",
                leading = Icons.Filled.BrightnessAuto,
                onClick = { toggleAuto(!isAuto, systemDark) },
                trailing = {
                    MgSwitch(
                        checked = isAuto,
                        onCheckedChange = { toggleAuto(it, systemDark) },
                    )
                },
            )
        }

        SectionCard(title = "Accent color") {
            AccentPicker(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            )
        }
    }
}

/**
 * Drag the thumb along the hue spectrum to pick any accent color, then along the shade
 * bar for lighter/darker. Writes straight to ThemeState.accent (whole app updates live).
 */
@Composable
private fun AccentPicker(modifier: Modifier = Modifier) {
    val sat = 0.85f
    var hue by remember { mutableFloatStateOf(ThemeState.accent.hue()) }
    var value by remember { mutableFloatStateOf(ThemeState.accent.value().coerceIn(0.4f, 1f)) }

    fun apply() {
        ThemeState.accent = Color.hsv(hue, sat, value)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(ThemeState.accent)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), CircleShape),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = ThemeState.accent.hex(),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Hue spectrum.
        GradientSlider(
            fraction = hue / 360f,
            colors = listOf(
                Color.Red, Color.Yellow, Color.Green,
                Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
            ),
            thumbColor = Color.hsv(hue, sat, 1f),
            onFraction = { f -> hue = f * 360f; apply() },
        )

        // Shade (lighter / darker).
        GradientSlider(
            fraction = ((value - 0.4f) / 0.6f).coerceIn(0f, 1f),
            colors = listOf(Color.hsv(hue, sat, 0.4f), Color.hsv(hue, sat, 1f)),
            thumbColor = ThemeState.accent,
            onFraction = { f -> value = 0.4f + f * 0.6f; apply() },
        )
    }
}

@Composable
private fun GradientSlider(
    fraction: Float,
    colors: List<Color>,
    thumbColor: Color,
    onFraction: (Float) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .clip(RoundedCornerShape(23.dp))
            .background(Brush.horizontalGradient(colors))
            .pointerInput(Unit) {
                detectTapGestures { o -> onFraction((o.x / widthPx).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onFraction((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            },
    ) {
        val thumbCenter = with(density) { (fraction.coerceIn(0f, 1f) * widthPx).toDp() }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbCenter - 16.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(thumbColor)
                .border(3.dp, Color.White, CircleShape),
        )
    }
}

private fun Color.hsv(): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
        out,
    )
    return out
}

private fun Color.hue(): Float = hsv()[0]
private fun Color.value(): Float = hsv()[2]
private fun Color.hex(): String {
    val r = (red * 255).roundToInt()
    val g = (green * 255).roundToInt()
    val b = (blue * 255).roundToInt()
    return "#%02X%02X%02X".format(r, g, b)
}

private fun toggleAuto(on: Boolean, systemDark: Boolean) {
    ThemeState.mode = when {
        on -> ThemeMode.AUTO
        systemDark -> ThemeMode.NIGHT
        else -> ThemeMode.DAY
    }
}

@Composable
private fun ModeCard(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    }
    Column(
        modifier = modifier
            .height(120.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(2.dp, border, RoundedCornerShape(18.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

