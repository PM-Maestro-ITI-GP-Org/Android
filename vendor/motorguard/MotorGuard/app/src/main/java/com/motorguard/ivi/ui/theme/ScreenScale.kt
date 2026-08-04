package com.motorguard.ivi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * How much to shrink or grow the whole UI so a fixed dashboard design fits an arbitrary panel.
 *
 * Every dimension in this app — the 92 dp rail, the 54 dp status bar, 28 dp card radii, 27 sp
 * titles, 76 dp touch targets — is drawn for the primary target: a **720 dp-tall** landscape
 * dashboard. On anything shorter those numbers are simply too big, and the symptom is the one
 * that keeps appearing: content overflowing its card.
 *
 * Rather than reinventing each dimension per screen size, the app scales **uniformly**. The
 * scale is applied by overriding `LocalDensity` in [MotorGuardTheme], so `dp` and `sp` alike are
 * converted through it and the entire design keeps its proportions at any size — the same
 * technique kiosk and automotive UIs normally use for a layout that must look identical
 * everywhere.
 *
 * Height drives it, not width: the layout is already flexible horizontally (the panes use
 * weights) and it is vertical rhythm that breaks first.
 *
 * On the real 1920x720 target this returns 1.0 and changes nothing.
 */
@Composable
fun rememberUiScale(): Float = uiScaleFor(LocalConfiguration.current.screenHeightDp.toFloat())

/** The pure part, split out so the clamp is unit-testable without a composition. */
fun uiScaleFor(heightDp: Float): Float {
    if (heightDp <= 0f) return 1f
    return (heightDp / DESIGN_HEIGHT_DP).coerceIn(MIN_SCALE, MAX_SCALE)
}

/** The primary target from the README: 1920x720, landscape. */
const val DESIGN_HEIGHT_DP = 720f

/**
 * Bounds so the scale stays sane at the extremes.
 *
 * The floor matters for touch: at 0.5 the docs' 76 dp target is still ~38 dp of real estate,
 * which is around a fingertip. Going below that would trade a tidy layout for controls a driver
 * cannot reliably hit, and that is not a trade this app should make.
 */
private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 1.25f
