package com.motorguard.ivi.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album theme is the one feature that can silently break the design system's contrast rule:
 * Palette hands back whatever is in the artwork, and plenty of covers are near-black or a muddy
 * maroon. These tests pin the correction, using the two worst realistic inputs.
 */
class AlbumPaletteTest {

    private val nightPanel = Tokens.Night.panel
    private val dayPanel = Tokens.Day.panel
    private val aa = 4.5

    @Test
    fun `a near-black cover colour is lifted to AA on the night panel`() {
        val murky = Color(0xFF1A0E12) // dark maroon, a very common album background
        assertTrue("test premise: should start out failing", contrastRatio(murky, nightPanel) < aa)

        val corrected = murky.ensureContrast(against = nightPanel, minRatio = aa, lighten = true)
        assertTrue(
            "corrected colour still fails AA: ${contrastRatio(corrected, nightPanel)}",
            contrastRatio(corrected, nightPanel) >= aa,
        )
    }

    @Test
    fun `a near-white cover colour is darkened to AA on the day panel`() {
        val pale = Color(0xFFF2EFEA)
        assertTrue("test premise: should start out failing", contrastRatio(pale, dayPanel) < aa)

        val corrected = pale.ensureContrast(against = dayPanel, minRatio = aa, lighten = false)
        assertTrue(
            "corrected colour still fails AA: ${contrastRatio(corrected, dayPanel)}",
            contrastRatio(corrected, dayPanel) >= aa,
        )
    }

    @Test
    fun `a colour that already passes is left alone`() {
        val vivid = Tokens.Night.accent
        assertTrue(contrastRatio(vivid, nightPanel) >= aa)
        assertEquals(vivid, vivid.ensureContrast(nightPanel, aa, lighten = true))
    }

    @Test
    fun `correction preserves hue, so it still reads as the album's colour`() {
        val murky = Color(0xFF3B0A18) // deep red
        val corrected = murky.ensureContrast(nightPanel, aa, lighten = true)

        // Red must remain the dominant channel; only lightness is allowed to move.
        assertTrue(
            "hue drifted: r=${corrected.red} g=${corrected.green} b=${corrected.blue}",
            corrected.red > corrected.green && corrected.red > corrected.blue,
        )
    }

    @Test
    fun `contrast correction terminates even for an impossible request`() {
        // Ratio 21 is pure black on pure white; nothing can reach it against a mid grey. The
        // step loop must give up rather than spin.
        val grey = Color(0xFF808080)
        val result = grey.ensureContrast(against = grey, minRatio = 21.0, lighten = true)
        assertTrue(result.red in 0f..1f)
    }

    @Test
    fun `a tinted background stays dark and keeps text readable`() {
        // A saturated cover must not turn the night background into a bright colour panel: the
        // README's whole night-glare argument depends on the base staying deep charcoal.
        val loud = Color(0xFFFF2D00) // vivid red
        val tinted = tintSurface(Tokens.Night.base, loud, Tokens.Night.onBase, lightness = 0.09f)

        assertTrue(
            "background must stay dark, got luminance-ish ${tinted.red + tinted.green + tinted.blue}",
            (tinted.red + tinted.green + tinted.blue) / 3f < 0.25f,
        )
        assertTrue(
            "body text must still clear AA on the tinted background: " +
                contrastRatio(Tokens.Night.onBase, tinted),
            contrastRatio(Tokens.Night.onBase, tinted) >= aa,
        )
    }

    @Test
    fun `a tinted background actually takes the album hue`() {
        val red = Color(0xFFFF2D00)
        val blue = Color(0xFF0033FF)
        val fromRed = tintSurface(Tokens.Night.base, red, Tokens.Night.onBase, lightness = 0.09f)
        val fromBlue = tintSurface(Tokens.Night.base, blue, Tokens.Night.onBase, lightness = 0.09f)

        // Not merely different from each other — each must lean towards its own hue.
        assertTrue("red cover should warm the base", fromRed.red > fromRed.blue)
        assertTrue("blue cover should cool the base", fromBlue.blue > fromBlue.red)
    }

    @Test
    fun `day background stays light`() {
        val dark = Color(0xFF120018)
        val tinted = tintSurface(Tokens.Day.base, dark, Tokens.Day.onBase, lightness = 0.95f)
        assertTrue(
            "day background must stay near-white",
            (tinted.red + tinted.green + tinted.blue) / 3f > 0.85f,
        )
        assertTrue(contrastRatio(Tokens.Day.onBase, tinted) >= aa)
    }

    @Test
    fun `contrast ratio matches the WCAG reference values`() {
        // Black on white is exactly 21:1, and any colour against itself is 1:1.
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.Red, Color.Red), 0.001)
    }
}
