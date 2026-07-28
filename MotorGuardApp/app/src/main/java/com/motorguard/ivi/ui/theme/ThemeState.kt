package com.motorguard.ivi.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Day / Night / Auto (follow the light sensor / system UiMode). */
enum class ThemeMode { DAY, NIGHT, AUTO }

/**
 * Selectable accent. Each has a Day and a Night variant; the theme picks one. The
 * [swatch] (vibrant night variant) is what the Settings picker shows.
 */
enum class AccentChoice(val label: String, val day: Color, val night: Color) {
    ELECTRIC_BLUE("Electric Blue", Color(0xFF0FA8D8), Color(0xFF56C9EF)),
    ICY_CYAN("Icy Cyan", Color(0xFF12A5C9), Color(0xFF80DCF8)),
    EMERALD("Emerald", Color(0xFF1FB56A), Color(0xFF38D17F)),
    AMBER("Amber", Color(0xFFD89A1E), Color(0xFFF5B942)),
    VIOLET("Violet", Color(0xFF7A5AF0), Color(0xFFA48CFF)),
    CORAL("Coral", Color(0xFFE24B43), Color(0xFFF46C64));

    val swatch: Color get() = night
}

/**
 * Single source of truth for user theme choices. Backed by Compose state, so every
 * surface that reads it (via [MotorGuardTheme]) recomposes when Settings changes it.
 * App-scoped singleton for now — persist to DataStore later.
 */
object ThemeState {
    var mode by mutableStateOf(ThemeMode.AUTO)
    var accent by mutableStateOf(AccentChoice.ELECTRIC_BLUE)
}
