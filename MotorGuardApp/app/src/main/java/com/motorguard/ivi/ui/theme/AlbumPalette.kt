package com.motorguard.ivi.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Album-art derived colour.
 *
 * Scoped to the Media surface and the Home now-playing card — deliberately not the whole app.
 * The nav rail, the status bar and the diagnostics severity colours keep the Modern Tech palette,
 * because a green/amber/red safety language that shifts hue with whatever is playing stops being
 * a language.
 *
 * The part that matters is [ensureContrast]. Palette returns whatever is in the artwork, and a
 * lot of album covers are dark maroon or near-black — used raw, those give unreadable text on a
 * `#121212` base. Every colour here is pushed until it clears WCAG AA against the surface it
 * will actually sit on, so the design system's contrast rule survives the feature.
 */
@Immutable
data class AlbumColors(
    /** Primary accent: progress fill, active states, the equaliser bars. */
    val accent: Color,
    /** Lower-emphasis companion for gradients and secondary marks. */
    val accentSoft: Color,
    /** A very low-alpha wash behind the media cards. Never text-bearing. */
    val surfaceTint: Color,
    /** False while showing the fallback, so callers can skip art-specific flourishes. */
    val fromArtwork: Boolean,
)

private val LocalAlbumColors = staticCompositionLocalOf<AlbumColors?> { null }

/**
 * Album colours where provided, otherwise the theme accent. Never null, so call sites do not
 * branch on whether artwork happened to load.
 */
object AlbumTheme {
    val colors: AlbumColors
        @Composable @ReadOnlyComposable get() = LocalAlbumColors.current ?: fallback()

    @Composable @ReadOnlyComposable
    private fun fallback(): AlbumColors {
        val base = MotorGuard.colors
        return AlbumColors(
            accent = base.accent,
            accentSoft = base.accent2,
            surfaceTint = Color.Transparent,
            fromArtwork = false,
        )
    }
}

/**
 * Extracts colours from [artwork] and provides them to [content].
 *
 * Transitions are animated: tracks change while the user is looking at the screen, and an
 * instant repaint of every accent reads as a glitch rather than as a response.
 */
@Composable
fun AlbumThemedContent(
    artwork: Bitmap?,
    content: @Composable () -> Unit,
) {
    val base = MotorGuard.colors
    var extracted by remember { mutableStateOf<AlbumColors?>(null) }

    LaunchedEffect(artwork) {
        extracted = artwork?.let { extractAlbumColors(it, base) }
    }

    val target = extracted ?: AlbumColors(
        accent = base.accent,
        accentSoft = base.accent2,
        surfaceTint = Color.Transparent,
        fromArtwork = false,
    )

    val spec = tween<Color>(durationMillis = TRANSITION_MS)
    val accent by animateColorAsState(target.accent, spec, label = "album-accent")
    val accentSoft by animateColorAsState(target.accentSoft, spec, label = "album-accent-soft")
    val tint by animateColorAsState(target.surfaceTint, spec, label = "album-tint")

    CompositionLocalProvider(
        LocalAlbumColors provides AlbumColors(
            accent = accent,
            accentSoft = accentSoft,
            surfaceTint = tint,
            fromArtwork = target.fromArtwork,
        ),
        content = content,
    )
}

/** Palette work is CPU-bound and runs off the main thread; on the Pi that matters. */
private suspend fun extractAlbumColors(
    bitmap: Bitmap,
    base: MotorGuardColors,
): AlbumColors = withContext(Dispatchers.Default) {
    val palette = runCatching {
        Palette.from(bitmap).clearFilters().maximumColorCount(PALETTE_COLORS).generate()
    }.getOrNull() ?: return@withContext AlbumColors(
        base.accent, base.accent2, Color.Transparent, fromArtwork = false,
    )

    // Vibrant first — it is the colour a person would point at and call "the album's colour".
    // Dominant is the safety net for covers with no saturated region at all.
    val seed = (
        palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch
            ?: palette.mutedSwatch
        )?.rgb?.let(::Color) ?: return@withContext AlbumColors(
        base.accent, base.accent2, Color.Transparent, fromArtwork = false,
    )

    val surface = if (base.isDark) Tokens.Night.panel else Tokens.Day.panel
    val accent = seed.ensureContrast(against = surface, minRatio = MIN_CONTRAST, lighten = base.isDark)

    AlbumColors(
        accent = accent,
        accentSoft = accent.shiftLightness(if (base.isDark) 0.12f else -0.12f),
        // Low enough that it reads as a tint on the glass rather than a coloured panel.
        surfaceTint = accent.copy(alpha = if (base.isDark) 0.10f else 0.07f),
        fromArtwork = true,
    )
}

/**
 * Push a colour's lightness until it clears [minRatio] against [against].
 *
 * Steps in HSL so the hue — the thing that actually makes it "the album's colour" — is
 * preserved; only how light it is changes. Gives up after a bounded number of steps rather than
 * looping, and at that point the colour is at white or black and nothing more can be done.
 */
internal fun Color.ensureContrast(against: Color, minRatio: Double, lighten: Boolean): Color {
    var candidate = this
    var steps = 0
    while (contrastRatio(candidate, against) < minRatio && steps < MAX_CONTRAST_STEPS) {
        candidate = candidate.shiftLightness(if (lighten) CONTRAST_STEP else -CONTRAST_STEP)
        steps++
    }
    return candidate
}

/**
 * Move a colour along the lightness axis, leaving hue and saturation alone.
 *
 * Hand-rolled rather than `androidx.core.graphics.ColorUtils` on purpose: that helper delegates
 * to `android.graphics.Color`, which is a stub in JVM unit tests and throws. Doing the HSL
 * conversion in pure Kotlin is about fifteen lines and makes the contrast guarantee testable
 * without Robolectric — worth it for a rule the design system depends on.
 */
internal fun Color.shiftLightness(delta: Float): Color {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val chroma = max - min

    val saturation = if (chroma == 0f) 0f else chroma / (1f - abs(2f * lightness - 1f))
    val hue = when {
        chroma == 0f -> 0f
        max == red -> 60f * (((green - blue) / chroma) % 6f)
        max == green -> 60f * (((blue - red) / chroma) + 2f)
        else -> 60f * (((red - green) / chroma) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    return hsl(hue, saturation, (lightness + delta).coerceIn(0f, 1f), alpha)
}

private fun hsl(hue: Float, saturation: Float, lightness: Float, alpha: Float): Color {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val second = chroma * (1f - abs(((hue / 60f) % 2f) - 1f))
    val match = lightness - chroma / 2f

    val (r, g, b) = when {
        hue < 60f -> Triple(chroma, second, 0f)
        hue < 120f -> Triple(second, chroma, 0f)
        hue < 180f -> Triple(0f, chroma, second)
        hue < 240f -> Triple(0f, second, chroma)
        hue < 300f -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    return Color(
        red = (r + match).coerceIn(0f, 1f),
        green = (g + match).coerceIn(0f, 1f),
        blue = (b + match).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

/** WCAG relative luminance. */
private fun Color.relativeLuminance(): Double {
    fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private const val PALETTE_COLORS = 24
private const val TRANSITION_MS = 650

/** WCAG AA for large text and UI components, per the README's contrast rule. */
private const val MIN_CONTRAST = 4.5
private const val CONTRAST_STEP = 0.04f
private const val MAX_CONTRAST_STEPS = 25
