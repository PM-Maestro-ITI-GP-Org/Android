package com.motorguard.ivi.ui.diagnostics.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
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

/** Columns either side averaged into each column of [smoothColumns] — display only, chosen so a
 *  10 s capture (~1,000 columns) reads as a trace rather than a comb without visibly rounding off
 *  a fault transient that survives at min/max-decimation width already. */
private const val SMOOTH_RADIUS = 2

/**
 * A box blur across adjacent COLUMNS of a [minMaxDecimate] trace — display smoothing, run after
 * decimation and nowhere near the samples themselves.
 *
 * [minMaxDecimate] exists to keep every spike alive through 200,000-to-1,000 reduction, which is
 * correct and is not what this touches. What it does not do anything about is a channel that is
 * genuinely this noisy sample to sample — raw, unscaled ADC vibration data with no motor turning
 * under it — where every column's min and max are already near the channel's full scale, so the
 * trace draws as a wall of full-height columns rather than a shape. Averaging min with neighbouring
 * min, and max with neighbouring max, never lets the two converge into a single flat line — a real
 * envelope's width survives — it only softens the column-to-column jitter within that width into
 * something that reads as one waveform.
 */
private fun smoothColumns(trace: FloatArray, columns: Int, radius: Int): FloatArray {
    if (radius <= 0 || columns <= 1) return trace
    val out = FloatArray(trace.size)
    for (c in 0 until columns) {
        var sumLo = 0f
        var sumHi = 0f
        var count = 0
        for (k in -radius..radius) {
            val idx = (c + k).coerceIn(0, columns - 1)
            sumLo += trace[idx * 2]
            sumHi += trace[idx * 2 + 1]
            count++
        }
        out[c * 2] = sumLo / count
        out[c * 2 + 1] = sumHi / count
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

/** Ticks on both axes. Five reads as a scale; more turns the plot into graph paper. */
private const val TICKS = 5

/** Width reserved for the vertical scale. Fixed rather than measured so the plot does not shift
 *  sideways when a label gains a digit — the same class of bug as the card's bouncing rows. */
private val AXIS_LABEL_WIDTH = 52.dp

/**
 * Draws one or more channels against [range], with a labelled scale on both axes.
 *
 * The vertical scale is shared by every channel and supplied by the caller, never fitted to the
 * data. Per-channel normalisation would give three phase currents the same height and scale the
 * imbalance between them — the whole reason to draw them together — out of existence; per-window
 * normalisation would make every window look alike, so scrubbing through a run would appear to
 * change nothing while the axis moved silently underneath.
 */
@Composable
internal fun WaveformPlot(
    channels: List<FloatArray>,
    colors: List<Color>,
    range: ClosedFloatingPointRange<Float>,
    fromIndex: Int,
    toIndex: Int,
    startSec: Float,
    windowSec: Float,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val axis = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val labelStyle = MaterialTheme.typography.labelSmall

    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier.width(AXIS_LABEL_WIDTH).fillMaxHeight(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                for (i in 0 until TICKS) {
                    val value = range.endInclusive -
                        (range.endInclusive - range.start) * i / (TICKS - 1)
                    Text(
                        text = axisValue(value),
                        style = labelStyle,
                        color = labelColor,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            PlotCanvas(
                channels = channels,
                colors = colors,
                range = range,
                fromIndex = fromIndex,
                toIndex = toIndex,
                grid = grid,
                axis = axis,
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(AXIS_LABEL_WIDTH))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (i in 0 until TICKS) {
                    Text(
                        text = timeValue(startSec + windowSec * i / (TICKS - 1), windowSec),
                        style = labelStyle,
                        color = labelColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlotCanvas(
    channels: List<FloatArray>,
    colors: List<Color>,
    range: ClosedFloatingPointRange<Float>,
    fromIndex: Int,
    toIndex: Int,
    grid: Color,
    axis: Color,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val columns = size.width.toInt().coerceAtLeast(1)
        val decimated = channels.map {
            smoothColumns(minMaxDecimate(it, fromIndex, toIndex, columns), columns, SMOOTH_RADIUS)
        }
        val span = (range.endInclusive - range.start).takeIf { it > 1e-6f } ?: 1f

        // Both axes get rules now that both are labelled: a gridline with no number against it is
        // decoration, and a number with no gridline is hard to read a value against.
        for (i in 1 until TICKS - 1) {
            val y = size.height * i / (TICKS - 1f)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            val x = size.width * i / (TICKS - 1f)
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
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
                // Clamped to the plot, so a value beyond the fixed scale runs along the edge
                // rather than being drawn outside it and silently disappearing. An instrument
                // pinned against its stop still tells you something.
                val yLo = (size.height * (1f - (lo - range.start) / span))
                    .coerceIn(0f, size.height)
                val yHi = (size.height * (1f - (hi - range.start) / span))
                    .coerceIn(0f, size.height)

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

/** Enough precision to separate adjacent ticks, and no more. */
private fun axisValue(value: Float): String {
    val magnitude = abs(value)
    return when {
        magnitude >= 10f -> String.format(Locale.US, "%.0f", value)
        magnitude >= 1f -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
}

/** Seconds, at whatever precision keeps neighbouring ticks distinct: a 50 ms window needs three
 *  decimals, and a ten-second one showing three would be unreadable noise. */
private fun timeValue(seconds: Float, windowSec: Float): String = when {
    windowSec < 0.2f -> String.format(Locale.US, "%.3f", seconds)
    windowSec < 2f -> String.format(Locale.US, "%.2f", seconds)
    else -> String.format(Locale.US, "%.1f", seconds)
}
