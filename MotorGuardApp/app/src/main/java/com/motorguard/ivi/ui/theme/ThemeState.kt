package com.motorguard.ivi.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.motorguard.ivi.data.LocalStore

/** Day / Night / Auto (follow the light sensor / system UiMode). */
enum class ThemeMode { DAY, NIGHT, AUTO }

/** The accents offered in Settings, shared so the swatches and the saved value cannot disagree. */
val PresetAccents = listOf(
    Color(0xFF56C9EF), // electric blue
    Color(0xFF38D17F), // green
    Color(0xFFF5B942), // amber
    Color(0xFFA48CFF), // purple
    Color(0xFFFF7A6B), // coral
    Color(0xFF4DE0D0), // teal
)

/**
 * Single source of truth for user theme choices. Backed by Compose state, so every surface that
 * reads it (via [MotorGuardTheme]) recomposes when Settings changes it, and written through to
 * [LocalStore] so the choice survives a reboot — a head unit that forgets the driver's theme
 * every time the car is switched off is not offering a theme, it is offering a default.
 *
 * Each property writes through to storage on assignment. Persisting in the setter rather than at
 * the call sites means a future screen that changes the theme cannot forget to save it.
 */
object ThemeState {

    private var _mode by mutableStateOf(ThemeMode.AUTO)
    var mode: ThemeMode
        get() = _mode
        set(value) {
            _mode = value
            LocalStore.putString(LocalStore.Keys.THEME_MODE, value.name)
        }

    /** The chosen accent. Used as-is unless [dynamicColor] is on and a cover is playing. */
    private var _accent by mutableStateOf(PresetAccents.first())
    var accent: Color
        get() = _accent
        set(value) {
            _accent = value
            LocalStore.putInt(LocalStore.Keys.THEME_ACCENT, value.toArgb())
        }

    /**
     * Let the playing album's cover drive the accent and the surface tint.
     *
     * Off by default: it is a striking effect, but it takes the accent out of the driver's hands,
     * and a setting that silently overrides an explicit choice is the wrong default. Turning it
     * off falls straight back to [accent] — which is why the chosen accent is kept alongside it
     * rather than being overwritten while the effect is on.
     */
    private var _dynamicColor by mutableStateOf(false)
    var dynamicColor: Boolean
        get() = _dynamicColor
        set(value) {
            _dynamicColor = value
            LocalStore.putBoolean(LocalStore.Keys.THEME_DYNAMIC, value)
        }

    /** Load saved choices. Call once at startup, after [LocalStore.init]. */
    fun restore() {
        // Assigns the backing fields directly: going through the setters would write every value
        // straight back out again, which is harmless but pointless I/O on every cold start.
        LocalStore.getString(LocalStore.Keys.THEME_MODE)?.let { saved ->
            _mode = runCatching { ThemeMode.valueOf(saved) }.getOrDefault(ThemeMode.AUTO)
        }
        val savedAccent = LocalStore.getInt(LocalStore.Keys.THEME_ACCENT, 0)
        if (savedAccent != 0) _accent = Color(savedAccent)
        _dynamicColor = LocalStore.getBoolean(LocalStore.Keys.THEME_DYNAMIC, false)
    }
}
