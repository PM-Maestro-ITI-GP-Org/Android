package com.motorguard.ivi.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Album-art derived colour, applied **app-wide**.
 *
 * Only the seed colour is stored here, not a finished palette. Contrast correction depends on
 * whether the app is in Day or Night, and keeping the raw seed means [MotorGuardTheme] can
 * re-derive on a theme switch instead of holding a stale, wrongly-corrected accent.
 *
 * What deliberately does **not** follow the artwork: `success` / `caution` / `critical`. Those
 * encode battery, tyre-pressure and brake severity. A green/amber/red language that shifts hue
 * with whatever is playing stops being a language, so they stay pinned to [Tokens].
 */
object AlbumThemeState {

    /** Dominant colour of the current cover, or null when there is none. */
    var seed: Color? by mutableStateOf(null)
        private set

    /** Called from a single place — [com.motorguard.ivi.MainActivity] — as the track changes. */
    fun setArtwork(seedColor: Color?) {
        seed = seedColor
    }
}

/**
 * Pulls the one colour that best represents [bitmap].
 *
 * Vibrant first, because it is the colour a person would point at and call "the album's colour";
 * dominant is the safety net for covers with no saturated region at all.
 *
 * Palette work is CPU-bound and runs off the main thread — on the Pi that matters.
 */
suspend fun extractAlbumSeed(bitmap: Bitmap): Color? = withContext(Dispatchers.Default) {
    val palette = runCatching {
        Palette.from(bitmap).clearFilters().maximumColorCount(PALETTE_COLORS).generate()
    }.getOrNull() ?: return@withContext null

    val swatch = palette.vibrantSwatch
        ?: palette.lightVibrantSwatch
        ?: palette.darkVibrantSwatch
        ?: palette.dominantSwatch
        ?: palette.mutedSwatch

    swatch?.rgb?.let(::Color)
}

/**
 * Push a colour's lightness until it clears [minRatio] against [against].
 *
 * Steps in HSL so the hue — the thing that actually makes it "the album's colour" — is
 * preserved; only how light it is changes. This is what keeps the design system's WCAG AA rule
 * intact when the accent is whatever happened to be on a cover: plenty of album art is
 * near-black or muddy maroon, and used raw that is unreadable on `#161B24`.
 *
 * Bounded rather than looping — past the limit the colour is at white or black and nothing more
 * can be done.
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
    val maxChannel = maxOf(red, green, blue)
    val minChannel = minOf(red, green, blue)
    val lightness = (maxChannel + minChannel) / 2f
    val chroma = maxChannel - minChannel

    val saturation = if (chroma == 0f) 0f else chroma / (1f - abs(2f * lightness - 1f))
    val hue = when {
        chroma == 0f -> 0f
        maxChannel == red -> 60f * (((green - blue) / chroma) % 6f)
        maxChannel == green -> 60f * (((blue - red) / chroma) + 2f)
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

/** WCAG AA for large text and UI components, per the README's contrast rule. */
internal const val MIN_CONTRAST = 4.5
private const val CONTRAST_STEP = 0.04f
private const val MAX_CONTRAST_STEPS = 25
