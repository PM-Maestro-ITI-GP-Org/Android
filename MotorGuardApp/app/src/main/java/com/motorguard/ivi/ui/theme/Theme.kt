package com.motorguard.ivi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
 * The parts of the palette Material's [androidx.compose.material3.ColorScheme] has no slot for:
 * the glassmorphism set and the semantic status trio. Reach for these via [MotorGuard.colors]
 * so a screen never has to ask "am I in night mode?" itself.
 */
@Immutable
data class MotorGuardColors(
    val glass: Color,
    val glassSoft: Color,
    val glassBorder: Color,
    val chip: Color,
    val highlight: Color,
    val accent: Color,
    val accent2: Color,
    val success: Color,
    val caution: Color,
    val critical: Color,
    val onBaseDim: Color,
    val isDark: Boolean,
)

private val NightExtras = MotorGuardColors(
    glass = Tokens.Night.glass,
    glassSoft = Tokens.Night.glassSoft,
    glassBorder = Tokens.Night.glassBorder,
    chip = Tokens.Night.chip,
    highlight = Tokens.Night.highlight,
    accent = Tokens.Night.accent,
    accent2 = Tokens.Night.accent2,
    success = Tokens.Night.success,
    caution = Tokens.Night.caution,
    critical = Tokens.Night.critical,
    onBaseDim = Tokens.Night.onBaseDim,
    isDark = true,
)

private val DayExtras = MotorGuardColors(
    glass = Tokens.Day.glass,
    glassSoft = Tokens.Day.glassSoft,
    glassBorder = Tokens.Day.glassBorder,
    chip = Tokens.Day.chip,
    highlight = Tokens.Day.highlight,
    accent = Tokens.Day.accent,
    accent2 = Tokens.Day.accent2,
    success = Tokens.Day.success,
    caution = Tokens.Day.caution,
    critical = Tokens.Day.critical,
    onBaseDim = Tokens.Day.onBaseDim,
    isDark = false,
)

private val LocalMotorGuardColors = staticCompositionLocalOf { NightExtras }

/** Companion to [MaterialTheme] for the tokens Material does not model. */
object MotorGuard {
    val colors: MotorGuardColors
        @Composable @ReadOnlyComposable get() = LocalMotorGuardColors.current
}

/**
 * Wrap every Compose surface (rail + each fragment) in this. It follows the system
 * Day/Night (UiMode / light sensor) automatically — no per-screen theming needed.
 */
@Composable
fun MotorGuardTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMotorGuardColors provides if (dark) NightExtras else DayExtras) {
        MaterialTheme(
            colorScheme = if (dark) NightColors else DayColors,
            content = content,
        )
    }
}
