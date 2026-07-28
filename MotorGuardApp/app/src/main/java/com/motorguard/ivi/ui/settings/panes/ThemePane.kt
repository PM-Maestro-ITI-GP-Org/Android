package com.motorguard.ivi.ui.settings.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.components.MgSwitch
import com.motorguard.ivi.ui.components.SectionCard
import com.motorguard.ivi.ui.components.SettingRow
import com.motorguard.ivi.ui.theme.AccentChoice
import com.motorguard.ivi.ui.theme.ThemeMode
import com.motorguard.ivi.ui.theme.ThemeState

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                AccentChoice.entries.forEach { choice ->
                    AccentSwatch(
                        choice = choice,
                        selected = ThemeState.accent == choice,
                        onClick = { ThemeState.accent = choice },
                    )
                }
            }
        }
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

@Composable
private fun AccentSwatch(
    choice: AccentChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(choice.swatch)
            .border(3.dp, ring, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = choice.label,
                tint = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
