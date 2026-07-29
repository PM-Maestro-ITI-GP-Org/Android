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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.motorguard.ivi.ui.components.SettingRow
import com.motorguard.ivi.ui.theme.ThemeMode
import com.motorguard.ivi.ui.theme.ThemeState
import kotlin.math.roundToInt

private val presetAccents = listOf(
    Color(0xFF56C9EF), // blue
    Color(0xFF38D17F), // green
    Color(0xFFF5B942), // amber
    Color(0xFFA48CFF), // purple
)

@Composable
fun ThemePane() {
    val isAuto = ThemeState.mode == ThemeMode.AUTO
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (ThemeState.mode) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.AUTO -> systemDark
    }
    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Contrast,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = "Theme & Display",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Day / Night preview cards
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ModeCard(
                label = "Day",
                icon = Icons.Filled.LightMode,
                selected = !effectiveDark,
                enabled = !isAuto,
                preview = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE9EDF2))),
                onClick = { ThemeState.mode = ThemeMode.DAY },
                modifier = Modifier.weight(1f),
            )
            ModeCard(
                label = "Night",
                icon = Icons.Filled.DarkMode,
                selected = effectiveDark,
                enabled = !isAuto,
                preview = Brush.verticalGradient(listOf(Color(0xFF1B2230), Color(0xFF0E1219))),
                onClick = { ThemeState.mode = ThemeMode.NIGHT },
                modifier = Modifier.weight(1f),
            )
        }

        // Auto day / night (inline row)
        SettingRow(
            title = "Auto day / night",
            subtitle = "Follow ambient light sensor",
            leading = Icons.Filled.BrightnessAuto,
            onClick = { toggleAuto(!isAuto, systemDark) },
            trailing = {
                MgSwitch(checked = isAuto, onCheckedChange = { toggleAuto(it, systemDark) })
            },
        )

        // Accent color (inline row with swatches)
        SettingRow(
            title = "Accent color",
            subtitle = "Ambient LED sync",
            leading = Icons.Filled.Palette,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    presetAccents.forEach { c ->
                        SwatchDot(
                            color = c,
                            selected = ThemeState.accent == c,
                            onClick = { ThemeState.accent = c },
                        )
                    }
                    CustomSwatch(
                        selected = ThemeState.accent !in presetAccents,
                        current = ThemeState.accent,
                        onClick = { showPicker = true },
                    )
                }
            },
        )
    }

    if (showPicker) {
        AccentSpectrumDialog(onDismiss = { showPicker = false })
    }
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
    preview: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .border(2.dp, border, RoundedCornerShape(20.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(preview),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SwatchDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Rainbow "custom" dot — opens the spectrum picker. Shows the current color if custom. */
@Composable
private fun CustomSwatch(selected: Boolean, current: Color, onClick: () -> Unit) {
    val rainbow = Brush.sweepGradient(
        listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
    )
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (selected) androidx.compose.ui.graphics.SolidColor(current) else rainbow)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun AccentSpectrumDialog(onDismiss: () -> Unit) {
    val sat = 0.85f
    var hue by remember { mutableFloatStateOf(ThemeState.accent.hue()) }
    var value by remember { mutableFloatStateOf(ThemeState.accent.value().coerceIn(0.4f, 1f)) }

    fun apply() {
        ThemeState.accent = Color.hsv(hue, sat, value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom accent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ThemeState.accent),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(ThemeState.accent.hex(), fontWeight = FontWeight.SemiBold)
                }
                GradientSlider(
                    fraction = hue / 360f,
                    colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                    thumbColor = Color.hsv(hue, sat, 1f),
                    onFraction = { f -> hue = f * 360f; apply() },
                )
                GradientSlider(
                    fraction = ((value - 0.4f) / 0.6f).coerceIn(0f, 1f),
                    colors = listOf(Color.hsv(hue, sat, 0.4f), Color.hsv(hue, sat, 1f)),
                    thumbColor = ThemeState.accent,
                    onFraction = { f -> value = 0.4f + f * 0.6f; apply() },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
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

private fun Color.hsvArray(): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
        out,
    )
    return out
}

private fun Color.hue(): Float = hsvArray()[0]
private fun Color.value(): Float = hsvArray()[2]
private fun Color.hex(): String =
    "#%02X%02X%02X".format((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())
