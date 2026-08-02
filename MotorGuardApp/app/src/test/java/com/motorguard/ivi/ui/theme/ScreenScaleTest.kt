package com.motorguard.ivi.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale is what lets one fixed dashboard design fit every panel, so its edges are worth
 * pinning — particularly the floor, which is a touch-safety limit rather than a taste call.
 */
class ScreenScaleTest {

    @Test
    fun `the primary target renders one to one`() {
        // 1920x720 is what the design is drawn for; it must not be scaled at all.
        assertEquals(1f, uiScaleFor(720f), 0.001f)
    }

    @Test
    fun `the other README resolutions scale down proportionally`() {
        // 1024x600 and 1280x720 are both listed as supported reflows.
        assertEquals(600f / 720f, uiScaleFor(600f), 0.001f)
        assertEquals(1f, uiScaleFor(720f), 0.001f)
    }

    @Test
    fun `a phone in landscape lands on the floor rather than vanishing`() {
        // ~360 dp is a typical phone in landscape — exactly half the design height.
        assertEquals(0.5f, uiScaleFor(360f), 0.001f)
    }

    @Test
    fun `the floor keeps touch targets usable`() {
        // Below the floor the docs' 76 dp target would shrink past a fingertip. Whatever the
        // panel, the smallest a target may become is 76 * MIN_SCALE.
        val smallest = 76f * uiScaleFor(1f)
        assertTrue("touch targets shrank to ${smallest}dp", smallest >= 36f)
    }

    @Test
    fun `very tall panels are capped rather than ballooning`() {
        assertTrue(uiScaleFor(4000f) <= 1.25f)
    }

    @Test
    fun `a nonsense height falls back to unscaled`() {
        assertEquals(1f, uiScaleFor(0f), 0.001f)
        assertEquals(1f, uiScaleFor(-100f), 0.001f)
    }
}
