// SettingsScreen — owner E (Voice Assistant section: owner D)
package com.motorguard.ivi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.voice.VoiceLanguage
import com.motorguard.ivi.ui.voice.VoicePrefs

/**
 * Real settings content starts here; everything else on this tab (Wi-Fi,
 * Bluetooth, Theme & Display, System) is still owner E's skeleton -- see
 * docs/06-settings.md. Voice Assistant is the one section that is wired up,
 * because it is also the one setting the voice pipeline itself reads: see
 * [VoicePrefs].
 */
@Composable
fun SettingsScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        item { VoiceAssistantSection() }
    }
}

@Composable
private fun VoiceAssistantSection() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(VoicePrefs.getLanguage(context)) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Voice Assistant",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Language you speak to the assistant in. Replies are always English.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VoiceLanguage.entries.forEach { language ->
                LanguageRow(
                    language = language,
                    selected = language == selected,
                    onClick = {
                        selected = language
                        VoicePrefs.setLanguage(context, language)
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(
    language: VoiceLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) colorScheme.primary.copy(alpha = 0.16f) else colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = language.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) colorScheme.primary else colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
