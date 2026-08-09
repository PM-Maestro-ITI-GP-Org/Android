package com.motorguard.ivi.ui.diagnostics.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.motorguard.ivi.data.vehicle.api.Hotspot

/**
 * Lifecycle of the car visual, surfaced so the screen can render the right chrome (spinner,
 * error text, nothing) without knowing anything about how the car is drawn. The renderer owns
 * the transition between these states; the screen only reads them.
 */
sealed interface CarRenderState {
    data object Loading : CarRenderState
    data object Ready : CarRenderState
    data class Failed(val message: String, val cause: Throwable?) : CarRenderState
}

/**
 * The swap seam between "how the car is drawn" and "everything else on the Diagnostics screen".
 * [DiagnosticsScreen][com.motorguard.ivi.ui.diagnostics.DiagnosticsScreen] and later steps
 * (hotspot dots, focus/zoom, live cards) are written against this interface only — the fact
 * that [Car3dRenderer] uses SceneView/Filament is not supposed to leak past this file. A
 * hypothetical `Car2dRenderer` (e.g. if the emulator GPU turns out too slow, see the phase-1
 * plan's risk table) would implement the same contract and nothing else in the app would change.
 *
 * Deviations from the original spec (§5), and why:
 * - `HotspotId` -> [Hotspot]: that is the enum's real name in this repo (T0 finding: reality
 *   wins over the spec's placeholder name).
 * - `screenPositionOf(id, focus): Offset` -> [screenPositionOf]`(hotspot): Offset?`: (a) the
 *   renderer owns the camera, so it already knows the current focus — accepting it as a
 *   parameter would let a caller lie about it; (b) in 3D a projection can genuinely fail
 *   (behind the camera, model not loaded yet, anchor name missing from the mesh) —
 *   returning `Offset.Zero` in that case would silently stack every dot in the corner instead
 *   of hiding them. A 2D renderer can always return a constant non-null and loses nothing by
 *   the type being nullable.
 * - `+ val state`: a 15 MB / ~200k-vertex model cannot load synchronously. Without this the
 *   screen has no way to tell "loading" from "broken" — the difference between a spinner and a
 *   blank void. A 2D renderer can return [CarRenderState.Ready] immediately.
 *
 * No hotspot/focus behaviour is implemented yet — but every parameter later steps need is
 * already in [Render]'s signature, so those steps add bodies, not signatures.
 */
interface CarRenderer {

    /**
     * Compose-observable. MUST be backed by `mutableStateOf` in every implementation so a
     * composable that reads it recomposes on change.
     */
    val state: CarRenderState

    /**
     * Draws the car, filling [modifier]'s bounds. [focus] is the currently focused hotspot
     * (null = overview framing). Implementations animate to the focus pose themselves.
     * [onBackgroundTap] fires when the user taps the car stage but not a component.
     */
    @Composable
    fun Render(
        focus: Hotspot?,
        onBackgroundTap: () -> Unit,
        modifier: Modifier,
    )

    /**
     * Where [hotspot]'s anchor currently projects, in pixels relative to the top-left of the
     * area [Render] was given. Null = not resolvable right now (model not loaded, anchor not
     * found, or the point is behind the camera).
     *
     * Read this from a composable that invalidates every frame; it is a live projection, not
     * a constant.
     *
     * Step 2 additions to this contract, now that a real implementation exists
     * ([Car3dRenderer.screenPositionOf]):
     * - Coordinates are px, top-left origin, relative to the area [Render] was given. Callers
     *   must place their overlay in the **same** `Box`, at the **same** size, as [Render] — the
     *   two must share a coordinate space for this to mean anything.
     * - It is **not** Compose-observable: calling it registers no snapshot read, so a composable
     *   that only reads this once will not recompose as the camera moves. Callers must poll it
     *   from a frame loop (e.g. `withFrameNanos`) themselves.
     * - Must be cheap enough to call 8x per frame — a 3D implementation should keep this to a
     *   couple of matrix-vector multiplies plus one projection, not a scene traversal.
     * - Must never throw and must never return a stale value once the underlying model/camera
     *   is gone (null, not a frozen last-known point).
     */
    fun screenPositionOf(hotspot: Hotspot): Offset?

    /**
     * How much [hotspot]'s anchor is on the side of the car facing AWAY from the viewer:
     * 0 = fully visible, 1 = fully hidden behind the vehicle, continuous ramp between.
     *
     * Live, view-dependent data — same contract as [screenPositionOf]: not Compose-observable,
     * must be polled from a frame loop, cheap enough to call 8x per frame, never throws, returns
     * 0f (not a stale value) once the model or camera is gone.
     *
     * Continuous rather than boolean on purpose: the car becomes user-rotatable in a later phase,
     * and a hard flip would pop as it turns.
     *
     * A renderer with no depth (e.g. a 2D side elevation) takes the default: nothing occludes.
     */
    fun occlusionOf(hotspot: Hotspot): Float = 0f

    /**
     * Orbit the view around the vehicle by [deltaDegrees], as a direct response to a drag.
     *
     * Applied immediately rather than animated — a rotation the finger is driving must track the
     * finger, and any camera animation in flight is abandoned. The offset persists, so a focus
     * transition afterwards approaches the component from wherever the user left the view.
     *
     * A renderer with a fixed viewpoint (a 2D elevation, say) takes the default and ignores it.
     */
    fun rotateBy(deltaDegrees: Float) = Unit

    /**
     * Tilt the view above or below the vehicle by [deltaDegrees], as a direct response to a
     * vertical drag. Positive raises the eye, so more of the roof comes into view.
     *
     * The vertical counterpart of [rotateBy], and immediate for the same reason. Unlike yaw this
     * is clamped even with nothing focused: elevation has two hard ends a turntable does not —
     * straight overhead, where the look-at basis degenerates against a world-Y up vector, and
     * below the ground plane, where the car is seen through a floor it does not have.
     */
    fun pitchBy(deltaDegrees: Float) = Unit

    /**
     * Paint the component behind [hotspot] in [color] — the severity colour the dot and the card
     * already use, applied to the part itself.
     *
     * Only the parts the diagnostics model plants inside the bodywork (the motor and the battery)
     * have a material of their own to recolour; anything belonging to the donor car keeps its own
     * paint, and a renderer is free to ignore hotspots it has no such part for. Idempotent and
     * cheap: callers re-send the whole set whenever severities change rather than diffing.
     *
     * Not Compose-observable and not animated — severity changes are discrete, and a component
     * that eased from green to red would spend the transition claiming a severity it never had.
     */
    fun setComponentColor(hotspot: Hotspot, color: Color) = Unit
}
