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
 * Wrap every Compose surface (rail + each fragment) in this. It follows the system
 * Day/Night (UiMode / light sensor) automatically — no per-screen theming needed.
 */
@Composable
fun MotorGuardTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) NightColors else DayColors,
        content = content,
    )
}
