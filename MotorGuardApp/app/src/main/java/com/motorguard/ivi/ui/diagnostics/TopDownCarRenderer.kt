package com.motorguard.ivi.ui.diagnostics

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import com.motorguard.ivi.R
import com.motorguard.ivi.data.vehicle.api.Hotspot

/**
 * 2D top-down car renderer (the Phase-1 implementation behind [CarRenderer]).
 *
 * Anchor fractions are tuned to `res/drawable/car_topdown.xml` (200×420dp, front
 * up, bbox ≈ x 34..166, y 20..398). To use different art, use the same intrinsic
 * fractions — they're fractions of the *image*, not the screen, so they survive
 * the aspect-fit letterboxing in [CarScene].
 */
class TopDownCarRenderer : CarRenderer {

    override val anchors = listOf(
        HotspotAnchor(Hotspot.TIRE_FL, Offset(0.205f, 0.252f), DiagnosticsIcons.Tire),
        HotspotAnchor(Hotspot.TIRE_FR, Offset(0.795f, 0.252f), DiagnosticsIcons.Tire),
        HotspotAnchor(Hotspot.MOTOR, Offset(0.50f, 0.357f), DiagnosticsIcons.Motor),
        HotspotAnchor(Hotspot.BATTERY, Offset(0.50f, 0.507f), DiagnosticsIcons.Battery),
        HotspotAnchor(Hotspot.DOORS, Offset(0.155f, 0.55f), DiagnosticsIcons.Doors),
        HotspotAnchor(Hotspot.BRAKES, Offset(0.845f, 0.66f), DiagnosticsIcons.Brakes),
        HotspotAnchor(Hotspot.TIRE_RL, Offset(0.205f, 0.748f), DiagnosticsIcons.Tire),
        HotspotAnchor(Hotspot.TIRE_RR, Offset(0.795f, 0.748f), DiagnosticsIcons.Tire),
    )

    override val idleViewTarget = CarViewTarget(scale = 1f, center = Offset(0.5f, 0.5f))

    override fun viewTargetFor(anchor: HotspotAnchor): CarViewTarget =
        CarViewTarget(scale = 2.7f, center = anchor.fraction)
}

/**
 * Aspect-fit rect of `imageAspect` inside `containerPx`, centered. Returns the rect
 * in container pixels; dots/labels map their [Offset] fractions through it.
 */
fun fitContentRect(containerPx: IntSize, imageAspect: Float): android.graphics.RectF {
    val cw = containerPx.width.toFloat()
    val ch = containerPx.height.toFloat()
    val containerAspect = cw / ch
    val w: Float
    val h: Float
    if (containerAspect > imageAspect) {
        h = ch
        w = h * imageAspect
    } else {
        w = cw
        h = w / imageAspect
    }
    val left = (cw - w) / 2f
    val top = (ch - h) / 2f
    return android.graphics.RectF(left, top, left + w, top + h)
}

/**
 * Car image with pan/zoom applied via graphicsLayer. [progress] is the animated
 * fly-to progress from [DiagnosticsViewModel] (0f = idle, 1f = focused view).
 */
@Composable
fun TopDownCarImage(
    renderer: TopDownCarRenderer,
    progress: Float,
    target: CarViewTarget,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val intrinsicAspect = 200f / 420f
        val contentRect = fitContentRect(
            IntSize(constraints.maxWidth, constraints.maxHeight), intrinsicAspect,
        )
        val contentCenterX = contentRect.centerX()
        val contentCenterY = contentRect.centerY()

        // How far the zoomed view has pushed the anchor to screen center.
        val anchorFracX = target.center.x
        val anchorFracY = target.center.y
        val targetScale = target.scale

        val anchorPx = Offset(
            contentRect.left + anchorFracX * contentRect.width(),
            contentRect.top + anchorFracY * contentRect.height(),
        )
        val txFull = (constraints.maxWidth / 2f) - anchorPx.x
        val tyFull = (constraints.maxHeight / 2f) - anchorPx.y

        val scale = lerp(1f, targetScale, progress)
        val tx = lerp(0f, txFull, progress)
        val ty = lerp(0f, tyFull, progress)

        Image(
            painter = painterResource(R.drawable.car_topdown),
            contentDescription = "Vehicle top-down view",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = tx
                    translationY = ty
                },
        )
    }
}

/** Inset margin (in intrinsic fractions) so the zoomed-in anchor doesn't hug the screen edge. */
fun zoomViewContentScale(anchorFraction: Offset, scale: Float): Offset {
    val margin = 0.22f
    val minX = margin / scale
    val maxX = 1f - margin / scale
    val minY = margin / scale
    val maxY = 1f - margin / scale
    return Offset(
        anchorFraction.x.coerceIn(minX, maxX),
        anchorFraction.y.coerceIn(minY, maxY),
    )
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
