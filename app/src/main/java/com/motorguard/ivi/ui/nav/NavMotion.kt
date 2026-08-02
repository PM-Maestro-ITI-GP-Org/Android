package com.motorguard.ivi.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How lively the Nav surface is allowed to be.
 *
 * The Pi 5's VideoCore VII is the constraint, so this is a dial rather than a constant: build
 * and demo at [RICH], and if the frame graph on the real hardware has room, flip to [SHOWCASE]
 * without touching a single composable. Every level obeys the README's rule — animate
 * transform and opacity only, never blur, shadow or layout.
 */
enum class AnimationLevel {
    /** Cross-fades and short slides only. The 60 fps insurance policy. */
    RESTRAINED,

    /** Default. Flowing route dashes, pulsing puck, maneuver cards that swap, rolling ETA digits. */
    RICH,

    /**
     * Everything in [RICH] plus a pitched 3D camera, a route that draws itself in on preview,
     * and a shimmer sweep across the glass. Measure on the Pi before enabling — this is the
     * level that can push the map below 60 fps.
     */
    SHOWCASE,
}

/**
 * Single source of truth for navigation motion: the level, the feature flags it implies, and
 * the shared specs. No composable defines its own duration, so retuning the whole surface is
 * one edit here.
 */
object NavMotion {

    /**
     * Backed by Compose state, so flipping this — from Settings, or from a debug switch after
     * measuring on the Pi — recomposes the Nav surface immediately.
     */
    var level: AnimationLevel by mutableStateOf(AnimationLevel.RICH)

    private val atLeastRich: Boolean get() = level != AnimationLevel.RESTRAINED
    private val showcase: Boolean get() = level == AnimationLevel.SHOWCASE

    // ---------------------------------------------------------------- feature flags

    /** Dashes that flow along the route line towards the destination. */
    val routeDashFlow: Boolean get() = atLeastRich

    /** The location puck breathes a soft halo instead of sitting still. */
    val puckPulse: Boolean get() = atLeastRich

    /** ETA / distance numerals slide-swap instead of hard-cutting. */
    val rollingNumerals: Boolean get() = atLeastRich

    /** The route animates in from the origin when the preview opens. */
    val routeDrawIn: Boolean get() = showcase

    /** A highlight sweeps across the glass panels once on entry. */
    val glassShimmer: Boolean get() = showcase

    /** Camera pitch while guiding. 0 keeps the cheap top-down projection. */
    val cameraTiltDegrees: Double get() = if (showcase) 45.0 else 0.0

    /** Zoom used while following the car. */
    val followZoom: Double get() = if (showcase) 17.2 else 16.4

    // ---------------------------------------------------------------- shared specs

    /** Standard easing for entrances — the design system's own `cubic-bezier(.2,.7,.2,1)`. */
    val decelerate: Easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

    /** Glass panels sliding in from their screen edge. */
    val panelEnter: EnterTransition
        get() = fadeIn(tween(PANEL_MS, easing = decelerate)) +
            slideInVertically(tween(PANEL_MS, easing = decelerate)) { height -> height / 4 }

    val panelExit: ExitTransition
        get() = fadeOut(tween(EXIT_MS, easing = LinearOutSlowInEasing)) +
            slideOutVertically(tween(EXIT_MS, easing = LinearOutSlowInEasing)) { height -> height / 5 }

    /** Value swaps inside a card (a new maneuver, a new distance). */
    fun <T> swap(): FiniteAnimationSpec<T> = tween(SWAP_MS, easing = decelerate)

    /** Progress bars, gauge fills, colour transitions. */
    fun <T> settle(): FiniteAnimationSpec<T> = tween(SETTLE_MS, easing = decelerate)

    /**
     * How long the map camera is given to reach each new position. Slightly longer than the
     * position tick so consecutive eases overlap into one continuous glide instead of
     * stepping. [AnimationLevel.RESTRAINED] snaps, which is cheapest of all.
     */
    val cameraEaseMs: Int get() = if (atLeastRich) 260 else 0

    /** Frame interval for the flowing-dash loop. ~17 fps is indistinguishable from 60 here. */
    const val DASH_FRAME_MS = 60L

    private const val PANEL_MS = 340
    private const val EXIT_MS = 200
    private const val SWAP_MS = 260
    private const val SETTLE_MS = 480
}
