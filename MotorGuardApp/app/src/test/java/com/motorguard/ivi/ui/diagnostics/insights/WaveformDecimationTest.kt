package com.motorguard.ivi.ui.diagnostics.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The decimator is what makes a 200,000-sample capture drawable in a 1,000 px plot, and the
 * property that matters is not compression but HONESTY: whatever is thrown away must not change
 * what the trace says. These pin that.
 */
class WaveformDecimationTest {

    @Test
    fun `every column reports the extremes of the samples it covers`() {
        val samples = floatArrayOf(0f, 5f, -3f, 1f, 2f, 9f, -1f, 0f)
        val out = minMaxDecimate(samples, 0, 8, columns = 2)

        assertEquals(-3f, out[0], 1e-6f) // first half min
        assertEquals(5f, out[1], 1e-6f) // first half max
        assertEquals(-1f, out[2], 1e-6f) // second half min
        assertEquals(9f, out[3], 1e-6f) // second half max
    }

    /**
     * The reason this is min/max and not every-Nth-sample. A 200 Hz sine decimated by stride would
     * alias into a slow beat that looks like a real signal; keeping both extremes preserves the
     * true envelope at any zoom level.
     */
    @Test
    fun `a sine keeps its full amplitude however far it is decimated`() {
        val n = 20_000
        val samples = FloatArray(n) { sin(2.0 * PI * 200.0 * it / n).toFloat() }

        listOf(2000, 500, 120, 40).forEach { columns ->
            val out = minMaxDecimate(samples, 0, n, columns)
            val lo = out.filterIndexed { i, _ -> i % 2 == 0 }.min()
            val hi = out.filterIndexed { i, _ -> i % 2 == 1 }.max()
            assertTrue("amplitude collapsed at $columns columns", hi > 0.99f && lo < -0.99f)
        }
    }

    /** A single-sample spike is exactly what fault data is made of, and it must survive any zoom. */
    @Test
    fun `an isolated spike survives decimation`() {
        val samples = FloatArray(50_000)
        samples[31_477] = 42f
        val out = minMaxDecimate(samples, 0, samples.size, columns = 300)
        assertEquals(42f, out.filterIndexed { i, _ -> i % 2 == 1 }.max(), 1e-6f)
    }

    @Test
    fun `a window narrower than the plot holds the last value instead of dropping to zero`() {
        val samples = floatArrayOf(7f, 7f, 7f)
        val out = minMaxDecimate(samples, 0, 3, columns = 12)
        assertTrue("a flat trace must not gap", out.all { it == 7f })
    }

    @Test
    fun `ranges outside the capture are clamped rather than throwing`() {
        val samples = floatArrayOf(1f, 2f, 3f)
        assertEquals(0, minMaxDecimate(samples, 0, 3, columns = 0).size)
        assertTrue(minMaxDecimate(samples, -50, 900, columns = 4).isNotEmpty())
        assertTrue(minMaxDecimate(samples, 2, 1, columns = 4).all { it == 0f })
    }

    @Test
    fun `the vertical range spans every channel so shared scaling is honest`() {
        val a = floatArrayOf(-2f, 4f)
        val b = floatArrayOf(-9f, 1f)
        val range = verticalRange(listOf(a, b))
        assertTrue(range.start < -9f)
        assertTrue(range.endInclusive > 4f)
    }

    /** A flat trace has no range to normalise against; it must draw as a line, not divide by zero. */
    @Test
    fun `a flat trace still gets a range`() {
        val range = verticalRange(listOf(floatArrayOf(3f, 3f, 3f)))
        assertTrue(range.endInclusive - range.start > 0.5f)
    }
}
