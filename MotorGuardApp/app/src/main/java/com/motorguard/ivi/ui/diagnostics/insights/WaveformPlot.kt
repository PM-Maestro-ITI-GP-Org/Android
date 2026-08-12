package com.motorguard.ivi.ui.diagnostics.insights

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Reduces a sample range to two values per pixel column: the minimum and the maximum falling in it.
 *
 * **This is what makes the plot possible at all.** A ten-second capture is 200,000 samples per
 * channel and the plot is about 1,000 px wide, so drawing every sample means ~200 line segments
 * per pixel — a draw call count that drops frames on a Pi 5, to produce ink no different from
 * drawing two.
 *
 * Min/max rather than sampling every Nth value: taking every 200th sample of a 200 Hz waveform
 * aliases it into whatever beat frequency the stride happens to make, so the curve on screen is a
 * lie that looks like a signal. Keeping both extremes of each column preserves the true envelope,
 * and any spike survives — which for fault data is the entire point.
 *
 * Returns `columns * 2` floats, laid out as min, max, min, max. Columns with no samples repeat the
 * previous column's values so the trace stays continuous rather than gapping.
 */
internal fun minMaxDecimate(
    samples: FloatArray,
    fromIndex: Int,
    toIndex: Int,
    columns: Int,
): FloatArray {
    if (columns <= 0) return FloatArray(0)
    val out = FloatArray(columns * 2)
    val from = fromIndex.coerceIn(0, samples.size)
    val to = toIndex.coerceIn(from, samples.size)
    val span = to - from
    if (span <= 0) return out

    var lastMin = samples[from]
    var lastMax = samples[from]
    for (c in 0 until columns) {
        val start = from + (span.toLong() * c / columns).toInt()
        val end = from + (span.toLong() * (c + 1) / columns).toInt()
        if (end <= start) {
            // Fewer samples than columns — zoomed in far enough that one sample spans several
            // pixels. Repeating holds the line flat instead of dropping it to zero.
            out[c * 2] = lastMin
            out[c * 2 + 1] = lastMax
            continue
        }
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in start until end) {
            val v = samples[i]
            lo = min(lo, v)
            hi = max(hi, v)
        }
        out[c * 2] = lo
        out[c * 2 + 1] = hi
        lastMin = lo
        lastMax = hi
    }
    return out
}

/**
 * Colours for [count] channels, derived from the theme's own [base] by rotating its hue.
 *
 * Rotation rather than a fixed set of three: the theme's `primary` and `secondary` are two shades
 * of the same blue here, so using them for two phases made phase A and phase C indistinguishable —
 * and telling the phases apart is the entire purpose of drawing them together. Spacing the hues
 * evenly guarantees separation whatever the theme becomes, and for a three-phase signal the 120
 * degrees on the colour wheel is the same 120 degrees the phases are separated by electrically.
 *
 * Saturation and value come from [base], so the traces still belong to this theme rather than
 * arriving from a palette of their own.
 */
internal fun phasePalette(base: Color, count: Int): List<Color> {
    if (count <= 1) return listOf(base)
    val r = base.red
    val g = base.green
    val b = base.blue
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    val delta = maxC - minC
    val hue = when {
        delta < 1e-4f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (maxC < 1e-4f) 0f else delta / maxC
    // A floor on saturation: a near-grey primary would rotate into three near-identical greys,
    // which is the failure this function exists to prevent.
    val s = saturation.coerceAtLeast(0.55f)
    val v = maxC.coerceAtLeast(0.65f)
    return List(count) { i ->
        Color.hsv((hue + 360f * i / count) % 360f, s, v)
    }
}

/** Smallest range that contains every column of every trace, with a little headroom. */
internal fun verticalRange(traces: List<FloatArray>): ClosedFloatingPointRange<Float> {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    traces.forEach { t ->
        t.forEach { v ->
            lo = min(lo, v)
            hi = max(hi, v)
        }
    }
    if (lo > hi) return -1f..1f
    // A flat trace has no range to scale against and would divide by zero; give it a band so it
    // draws as a line through the middle rather than vanishing.
    if (hi - lo < 1e-4f) return (lo - 1f)..(hi + 1f)
    val pad = (hi - lo) * 0.08f
    return (lo - pad)..(hi + pad)
}

/**
 * Draws one or more channels over a shared vertical scale.
 *
 * Shared, not per-channel: three phase currents normalised independently would each fill the height
 * and the imbalance between them — the whole reason to draw them together — would be scaled out of
 * existence.
 */
@Composable
internal fun WaveformPlot(
    channels: List<FloatArray>,
    colors: List<Color>,
    fromIndex: Int,
    toIndex: Int,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val axis = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)

    Canvas(modifier) {
        val columns = size.width.toInt().coerceAtLeast(1)
        val decimated = channels.map { minMaxDecimate(it, fromIndex, toIndex, columns) }
        val range = verticalRange(decimated)
        val span = range.endInclusive - range.start

        // Horizontal rules only. Vertical ones would imply a time grid, and the x scale changes
        // with every signal group, so a fixed vertical spacing would mean different things in
        // different views.
        for (i in 1 until 4) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (range.start < 0f && range.endInclusive > 0f) {
            val zeroY = size.height * (1f - (0f - range.start) / span)
            drawLine(axis, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.5f)
        }

        val stroke = 1.6.dp.toPx()
        decimated.forEachIndexed { index, trace ->
            val color = colors[index % colors.size]
            var prevX = 0f
            var prevMidY = Float.NaN
            for (c in 0 until columns) {
                val lo = trace[c * 2]
                val hi = trace[c * 2 + 1]
                val x = c.toFloat()
                val yLo = size.height * (1f - (lo - range.start) / span)
                val yHi = size.height * (1f - (hi - range.start) / span)

                // The vertical extent of everything that fell in this column, drawn once.
                if (yLo - yHi > 0.5f) {
                    drawLine(color, Offset(x, yLo), Offset(x, yHi), strokeWidth = stroke, cap = StrokeCap.Butt)
                }

                // ...and a segment joining this column to the last.
                //
                // Not decoration: zoomed in far enough that a column holds ONE sample, min equals
                // max, the bar above has zero height and draws nothing at all. The plot came up
                // blank at a 50 ms window for exactly that reason. Joining midpoints degenerates
                // to an ordinary line plot when the data is sparse and is hidden inside the bars
                // when it is dense, so one path covers every zoom level.
                val midY = (yLo + yHi) / 2f
                if (!prevMidY.isNaN()) {
                    drawLine(color, Offset(prevX, prevMidY), Offset(x, midY), strokeWidth = stroke)
                }
                prevX = x
                prevMidY = midY
            }
        }
    }
}
