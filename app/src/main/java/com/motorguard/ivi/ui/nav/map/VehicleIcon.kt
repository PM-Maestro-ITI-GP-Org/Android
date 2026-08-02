package com.motorguard.ivi.ui.nav.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.ColorInt

/**
 * The vehicle arrow, defined once and drawn three ways: as a Compose path in
 * [com.motorguard.ivi.ui.nav.components.VehiclePuck], as a Compose path in [CanvasMapSurface],
 * and as an Android bitmap for MapLibre's symbol layer.
 *
 * Keeping the geometry here rather than duplicating it in three renderers is what stops the
 * "car" in the route overview from being a visibly different shape to the one in guidance.
 */
internal object VehicleArrow {

    /**
     * Outline as (x, y) offsets from the centre, in units of the arrow's half-size, pointing
     * up (-y). Sharp nose, swept-back tips, notched tail.
     */
    val outline: List<Pair<Float, Float>> = listOf(
        0f to -1f,
        0.72f to 0.78f,
        0f to 0.34f,
        -0.72f to 0.78f,
    )

    /**
     * Renders the arrow for MapLibre's `addImage`. Built at the display density and used with
     * `iconSize(1f)` so it lands on screen at [sizeDp] regardless of the panel's DPI.
     *
     * @param outlineColor a contrasting rim; without it the arrow disappears over a road of a
     *        similar tone, which on a dark map is most of them.
     */
    fun bitmap(
        density: Float,
        sizeDp: Float,
        @ColorInt fillColor: Int,
        @ColorInt outlineColor: Int,
        @ColorInt haloColor: Int,
    ): Bitmap {
        val sizePx = (sizeDp * density).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val centre = sizePx / 2f
        val radius = sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.style = Paint.Style.FILL
        paint.color = haloColor
        canvas.drawCircle(centre, centre, radius * 0.44f, paint)

        val path = Path().apply {
            val scale = radius * ARROW_SCALE
            outline.forEachIndexed { index, (x, y) ->
                val px = centre + x * scale
                val py = centre + y * scale
                if (index == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.18f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = outlineColor
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawPath(path, paint)

        return bitmap
    }

    /** Arrow size relative to the icon's radius. Leaves room for the halo. */
    const val ARROW_SCALE = 0.5f

    /** On-map marker size. Smaller than the 76 dp follow puck — it is a locator, not the focus. */
    const val MAP_MARKER_DP = 52f
}
