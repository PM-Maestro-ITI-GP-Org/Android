package com.motorguard.ivi.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
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
 *
 * It also applies the **album accent app-wide**. Because every fragment is wrapped in this, and
 * because the rail, gauges and controls all read `MotorGuard.colors.accent` or
 * `MaterialTheme.colorScheme.primary`, tinting here is what makes the whole system follow the
 * music rather than just the Media tab.
 *
 * Two things are deliberately held back:
 *  - `success` / `caution` / `critical` stay pinned to [Tokens]. They mean battery, tyre and
 *    brake severity; a safety language that changes hue with the current track is not a language.
 *  - the accent is contrast-corrected against the panel it will sit on before it is used, so an
 *    album cover cannot push the UI below WCAG AA.
 */
@Composable
fun MotorGuardTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val base = if (dark) NightExtras else DayExtras
    val panel = if (dark) Tokens.Night.panel else Tokens.Day.panel

    // Re-derived from the raw seed on every theme flip, so Day and Night each get a correction
    // appropriate to their own background.
    val seed = AlbumThemeState.seed
    val targetAccent = seed?.ensureContrast(panel, MIN_CONTRAST, lighten = dark) ?: base.accent
    val targetAccent2 = seed?.let { targetAccent.shiftLightness(if (dark) 0.12f else -0.12f) }
        ?: base.accent2

    // Tracks change while the driver is looking at the screen; repainting every accent instantly
    // reads as a glitch rather than a response.
    val spec = tween<Color>(durationMillis = ACCENT_TRANSITION_MS)
    val accent by animateColorAsState(targetAccent, spec, label = "app-accent")
    val accent2 by animateColorAsState(targetAccent2, spec, label = "app-accent-2")

    val colors = base.copy(accent = accent, accent2 = accent2)
    val scheme = (if (dark) NightColors else DayColors).copy(
        primary = accent,
        secondary = accent2,
    )

    CompositionLocalProvider(LocalMotorGuardColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private const val ACCENT_TRANSITION_MS = 650
