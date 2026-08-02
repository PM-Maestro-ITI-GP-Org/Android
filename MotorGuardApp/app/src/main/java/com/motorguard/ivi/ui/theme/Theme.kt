package com.motorguard.ivi.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

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
    /** Nav-rail background. Follows the album hue like the other surfaces. */
    val railBg: Color,
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
    railBg = Tokens.Night.railBg,
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
    railBg = Tokens.Day.railBg,
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
fun MotorGuardTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    // Day/Night from Settings ([ThemeState]); [forceDark] overrides it (voice overlay scrim).
    val dark = forceDark ?: when (ThemeState.mode) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val base = if (dark) NightExtras else DayExtras
    // Only follow the cover when the driver asked for it; otherwise every derived colour below
    // falls back to their chosen accent, which is exactly the null-seed path.
    val seed = AlbumThemeState.seed.takeIf { ThemeState.dynamicColor }
    val onSurface = if (dark) Tokens.Night.onBase else Tokens.Day.onBase

    // Surfaces take the album's hue but keep their own lightness — see tintSurface. Deriving
    // these first matters: the accent is then corrected against the surface it will actually sit
    // on, rather than against the untinted token it no longer matches.
    val targetBase = seed?.let {
        tintSurface(if (dark) Tokens.Night.base else Tokens.Day.base, it, onSurface, surfaceL(dark))
    } ?: (if (dark) Tokens.Night.base else Tokens.Day.base)

    val targetPanel = seed?.let {
        tintSurface(if (dark) Tokens.Night.panel else Tokens.Day.panel, it, onSurface, panelL(dark))
    } ?: (if (dark) Tokens.Night.panel else Tokens.Day.panel)

    val targetRail = seed?.let {
        tintSurface(if (dark) Tokens.Night.railBg else Tokens.Day.railBg, it, onSurface, railL(dark))
    } ?: (if (dark) Tokens.Night.railBg else Tokens.Day.railBg)

    // Re-derived from the raw seed on every theme flip, so Day and Night each get a correction
    // appropriate to their own background.
    // With a track: the album accent, contrast-corrected. Without: the Settings accent.
    val targetAccent = seed?.ensureContrast(targetPanel, MIN_CONTRAST, lighten = dark) ?: ThemeState.accent
    val targetAccent2 = seed?.let { targetAccent.shiftLightness(if (dark) 0.12f else -0.12f) }
        ?: base.accent2

    // Tracks change while the driver is looking at the screen; repainting instantly reads as a
    // glitch rather than a response.
    val spec = tween<Color>(durationMillis = ACCENT_TRANSITION_MS)
    val accent by animateColorAsState(targetAccent, spec, label = "app-accent")
    val accent2 by animateColorAsState(targetAccent2, spec, label = "app-accent-2")
    val background by animateColorAsState(targetBase, spec, label = "app-background")
    val panel by animateColorAsState(targetPanel, spec, label = "app-panel")
    val rail by animateColorAsState(targetRail, spec, label = "app-rail")

    val colors = base.copy(accent = accent, accent2 = accent2, railBg = rail)
    val scheme = (if (dark) NightColors else DayColors).copy(
        primary = accent,
        secondary = accent2,
        background = background,
        surface = panel,
    )

    // Uniform scale so the fixed 1920x720 design fits whatever panel it lands on. Overriding
    // density converts every dp AND sp through it, so proportions, type and touch targets all
    // shrink or grow together instead of each needing its own breakpoint. See rememberUiScale.
    val density = LocalDensity.current
    val scale = rememberUiScale()
    val scaledDensity = remember(density, scale) {
        Density(density.density * scale, density.fontScale)
    }

    CompositionLocalProvider(
        LocalMotorGuardColors provides colors,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(colorScheme = scheme) {
            // The host layout paints a static @color/base, which cannot follow the music. Painting
            // here instead means every surface wrapped in this theme — including the placeholder
            // fragments nobody has built yet — gets the tinted background for free.
            Box(Modifier.background(scheme.background)) { content() }
        }
    }
}

// Target lightness per surface. Night stays deep charcoal and Day stays near-white — the album
// changes the hue of these surfaces, never how bright they are. That is what keeps the README's
// "deep charcoal bases reduce night glare" true with the feature switched on.
private fun surfaceL(dark: Boolean) = if (dark) 0.09f else 0.95f
private fun panelL(dark: Boolean) = if (dark) 0.14f else 0.99f
private fun railL(dark: Boolean) = if (dark) 0.07f else 0.90f

private const val ACCENT_TRANSITION_MS = 650
