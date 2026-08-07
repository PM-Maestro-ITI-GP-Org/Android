package com.motorguard.ivi.ui.diagnostics

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import com.motorguard.ivi.data.vehicle.api.Hotspot

/**
 * Hotspot geometry in intrinsic image fractions (0f..1f, front of car = y=0).
 * Drive everything from these so swapping a 3D renderer later (Phase 3 polish)
 * doesn't touch the interaction logic — implement the same anchors against a
 * GLB/SceneView scene and `DiagnosticsScreen` won't know the difference.
 */
data class HotspotAnchor(
    val hotspot: Hotspot,
    val fraction: Offset,   // x=fraction of width, y=fraction of height
    val icon: ImageVector,
    val label: String = hotspot.label,
)

data class CarViewTarget(
    val scale: Float,
    /** Center of the view in intrinsic fractions. */
    val center: Offset,
)

interface CarRenderer {
    /** Fixed, per-hotspot anchor positions — the ZoomPose math keys off these. */
    val anchors: List<HotspotAnchor>

    val idleViewTarget: CarViewTarget

    /** Crop/zoom the camera to show [anchor] nicely in a container of [sizePx]. */
    fun viewTargetFor(anchor: HotspotAnchor): CarViewTarget = CarViewTarget(
        scale = 2.6f,
        center = anchor.fraction,
    )

    /** Composable hook to render the car body inside a [Modifier]-scoped box. */
    // The concrete renderer (TopDownCarRenderer) exposes a @Composable via its
    // own signature; the interface only guarantees the geometry above.
}
