package com.motorguard.ivi.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Day / Night / Auto (follow the light sensor / system UiMode). */
enum class ThemeMode { DAY, NIGHT, AUTO }

/**
 * Single source of truth for user theme choices. Backed by Compose state, so every
 * surface that reads it (via [MotorGuardTheme]) recomposes when Settings changes it.
 * The accent is any color — the Settings picker sets it from a hue/shade spectrum.
 * App-scoped singleton for now — persist to DataStore later.
 */
object ThemeState {
    var mode by mutableStateOf(ThemeMode.AUTO)
    var accent by mutableStateOf(Color(0xFF56C9EF)) // Electric Blue default
}
