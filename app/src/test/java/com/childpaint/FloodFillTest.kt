package com.childpaint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [FloodFill.fillPixels] directly — the pure, android-free core of the
 * paint-bucket algorithm — so these run as plain JVM unit tests. The Bitmap-based
 * [FloodFill.fill] wrapper is untestable here since android.graphics.Bitmap is a
 * throwing stub outside an Android runtime; it is not exercised by this file.
 */
class FloodFillTest {

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()
    private val RED = 0xFFFF0000.toInt()

    // Halfway between BLACK and WHITE on every channel — stands in for an
    // anti-aliased pixel straddling a line/paper edge. Distance to WHITE is 127
    // on each channel.
    private val GRAY = 0xFF808080.toInt()

    /** Builds a pixel array + dimensions from one-character-per-pixel rows. */
    private fun canvasOf(art: String, colors: Map<Char, Int>): Triple<IntArray, Int, Int> {
        val lines = art.trim('\n').split("\n")
        val h = lines.size
        val w = lines[0].length
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val line = lines[y]
            require(line.length == w) { "ragged art: row $y has length ${line.length}, expected $w" }
            for (x in 0 until w) {
                pixels[y * w + x] = colors.getValue(line[x])
            }
        }
        return Triple(pixels, w, h)
    }

    @Test
    fun `fills enclosed rectangle and leaves border and outside untouched`() {
        val (pixels, w, h) = canvasOf(
            """
            .......
            .#####.
            .#...#.
            .#...#.
            .#...#.
            .#####.
            .......
            """.trimIndent(),
            mapOf('.' to WHITE, '#' to BLACK)
        )

        FloodFill.fillPixels(pixels, w, h, 3, 3, RED, tolerance = 0)

        val (expected, _, _) = canvasOf(
            """
            .......
            .#####.
            .#RRR#.
            .#RRR#.
            .#RRR#.
            .#####.
            .......
            """.trimIndent(),
            mapOf('.' to WHITE, '#' to BLACK, 'R' to RED)
        )
        assertArrayEquals(expected, pixels)
    }

    @Test
    fun `gap in border leaks the fill outside`() {
        // Same box as above, but the top wall has a one-pixel gap at column 3.
        val (pixels, w, h) = canvasOf(
            """
            .......
            .##.##.
            .#...#.
            .#...#.
            .#...#.
            .#####.
            .......
            """.trimIndent(),
            mapOf('.' to WHITE, '#' to BLACK)
        )

        FloodFill.fillPixels(pixels, w, h, 3, 3, RED, tolerance = 0)

        fun at(x: Int, y: Int) = pixels[y * w + x]

        // The fill escaped through the gap into the exterior ring...
        assertEquals("gap pixel itself should be filled", RED, at(3, 1))
        assertEquals("exterior corner should be reached via the leak", RED, at(0, 0))
        assertEquals("far exterior wall should be reached too", RED, at(6, 6))
        // ...but walls away from the gap are untouched — this isn't a naive
        // "fill everything" bug, connectivity through the gap is what did it.
        assertEquals("left wall away from the gap stays intact", BLACK, at(1, 3))
        assertEquals("right wall away from the gap stays intact", BLACK, at(5, 3))
        assertEquals("bottom wall stays intact", BLACK, at(3, 5))
    }

    @Test
    fun `tolerance is what lets the fill cross a blended pixel`() {
        // .  .  G  .  .   -- a single anti-aliased pixel bridges two paper regions
        val (zeroPixels, w, h) = canvasOf(
            "..G..",
            mapOf('.' to WHITE, 'G' to GRAY)
        )
        val withTolerancePixels = zeroPixels.copyOf()

        // Tolerance 0: |WHITE - GRAY| = 127 per channel, so the scan stops dead
        // at the blend and the far side of the row is never reached.
        FloodFill.fillPixels(zeroPixels, w, h, 0, 0, RED, tolerance = 0)
        assertArrayEquals(intArrayOf(RED, RED, GRAY, WHITE, WHITE), zeroPixels)

        // Tolerance 127 covers exactly that per-channel distance, so the same
        // start now crosses the blend and reaches the rest of the row.
        FloodFill.fillPixels(withTolerancePixels, w, h, 0, 0, RED, tolerance = 127)
        assertArrayEquals(intArrayOf(RED, RED, RED, RED, RED), withTolerancePixels)
    }

    @Test
    fun `target color equal to new color is a no-op`() {
        val (pixels, w, h) = canvasOf(
            """
            .#.
            #.#
            .#.
            """.trimIndent(),
            mapOf('.' to WHITE, '#' to BLACK)
        )
        val original = pixels.copyOf()

        FloodFill.fillPixels(pixels, w, h, 0, 0, WHITE, tolerance = 64)

        assertArrayEquals(original, pixels)
    }

    @Test
    fun `filling a uniform canvas sets every pixel and terminates`() {
        val w = 4
        val h = 4
        val pixels = IntArray(w * h) { WHITE }

        FloodFill.fillPixels(pixels, w, h, 1, 2, RED, tolerance = 0)

        assertArrayEquals(IntArray(w * h) { RED }, pixels)
    }

    @Test
    fun `handles the far boundary pixels, not just the origin`() {
        // Starting exactly on the extreme valid corners (the boundary the
        // Bitmap-based wrapper's bounds check lets through) must work; there is
        // no separate bounds check to test here since fillPixels trusts its caller.
        val w = 3
        val h = 3

        val topLeft = IntArray(w * h) { WHITE }
        FloodFill.fillPixels(topLeft, w, h, 0, 0, RED, tolerance = 0)
        assertArrayEquals(IntArray(w * h) { RED }, topLeft)

        val bottomRight = IntArray(w * h) { WHITE }
        FloodFill.fillPixels(bottomRight, w, h, w - 1, h - 1, RED, tolerance = 0)
        assertArrayEquals(IntArray(w * h) { RED }, bottomRight)
    }

    @Test
    fun `1x1 canvas`() {
        val pixels = intArrayOf(WHITE)

        FloodFill.fillPixels(pixels, 1, 1, 0, 0, RED, tolerance = 0)

        assertArrayEquals(intArrayOf(RED), pixels)
    }
}
