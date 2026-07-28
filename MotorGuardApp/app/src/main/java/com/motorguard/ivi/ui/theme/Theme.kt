package com.motorguard.ivi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val NightColors = darkColorScheme(
    primary = Tokens.Night.accent,
    secondary = Tokens.Night.accent2,
    background = Tokens.Night.base,
    surface = Tokens.Night.panel,
    onPrimary = Tokens.Night.base,
    onBackground = Tokens.Night.onBase,
    onSurface = Tokens.Night.onBase,
    error = Tokens.Night.critical,
)

private val DayColors = lightColorScheme(
    primary = Tokens.Day.accent,
    secondary = Tokens.Day.accent2,
    background = Tokens.Day.base,
    surface = Tokens.Day.panel,
    onPrimary = Tokens.Day.base,
    onBackground = Tokens.Day.onBase,
    onSurface = Tokens.Day.onBase,
    error = Tokens.Day.critical,
)

/**
 * Wrap every Compose surface (rail + each fragment) in this. Day/Night and the accent
 * come from [ThemeState] — which the Settings screen writes to — so changing the theme
 * anywhere updates the whole app. AUTO follows the system UiMode / light sensor.
 */
@Composable
fun MotorGuardTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeState.mode) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val base = if (dark) NightColors else DayColors

    MaterialTheme(
        colorScheme = base.copy(primary = ThemeState.accent),
        content = content,
    )
}
