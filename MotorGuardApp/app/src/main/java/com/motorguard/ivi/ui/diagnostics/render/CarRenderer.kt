package com.motorguard.ivi.ui.diagnostics.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
}
