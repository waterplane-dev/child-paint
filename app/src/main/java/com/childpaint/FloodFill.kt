package com.childpaint

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * Scanline flood fill (the paint-bucket tool).
 *
 * Tolerance matters here: brush and shape strokes are anti-aliased, so the pixels
 * right at an outline's edge are blends between the line colour and the paper.
 * An exact-match fill would stop dead at the first blended pixel and leave a
 * visible halo, so we match within a per-channel distance instead.
 */
object FloodFill {

    const val DEFAULT_TOLERANCE = 64

    /**
     * One scratch buffer, reused across fills. A full-screen fill otherwise
     * allocates ~10 MB of ints per call, and undo replays every fill still in the
     * history — enough churn to stall on GC. Fills only ever run from the view's
     * touch handling and from replay, both on the UI thread, so sharing is safe.
     */
    private var scratch = IntArray(0)

    private fun scratchOf(size: Int): IntArray {
        if (scratch.size < size) scratch = IntArray(size)
        return scratch
    }

    fun fill(bitmap: Bitmap, startX: Int, startY: Int, newColor: Int, tolerance: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (startX < 0 || startX >= w || startY < 0 || startY >= h) return

        val pixels = scratchOf(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        fillPixels(pixels, w, h, startX, startY, newColor, tolerance)

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /**
     * The pure scanline algorithm, operating in place on an ARGB_8888 pixel
     * array. No android.* references here, so it runs as a plain JVM unit test —
     * the Bitmap-based [fill] above is just a thin getPixels/setPixels wrapper
     * around this.
     */
    internal fun fillPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        newColor: Int,
        tolerance: Int,
    ) {
        val w = width
        val h = height

        val target = pixels[startY * w + startX]
        if (target == newColor) return

        val targetA = (target ushr 24) and 0xFF
        val targetR = (target ushr 16) and 0xFF
        val targetG = (target ushr 8) and 0xFF
        val targetB = target and 0xFF

        // Filled pixels take newColor, and newColor never matches, so each pixel is
        // visited once and the scan always terminates.
        fun matches(p: Int): Boolean {
            if (p == newColor) return false
            if (p == target) return true
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val d = max(
                max(abs(r - targetR), abs(g - targetG)),
                max(abs(b - targetB), abs(a - targetA))
            )
            return d <= tolerance
        }

        val stack = ArrayDeque<Int>()
        stack.addLast(startY * w + startX)

        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            val y = idx / w
            var x = idx % w
            val row = y * w

            if (!matches(pixels[row + x])) continue

            // Walk to the left edge of this span.
            while (x > 0 && matches(pixels[row + x - 1])) x--

            var spanAbove = false
            var spanBelow = false
            while (x < w && matches(pixels[row + x])) {
                pixels[row + x] = newColor

                if (y > 0) {
                    val above = matches(pixels[row - w + x])
                    if (above && !spanAbove) {
                        stack.addLast((y - 1) * w + x)
                        spanAbove = true
                    } else if (!above) {
                        spanAbove = false
                    }
                }
                if (y < h - 1) {
                    val below = matches(pixels[row + w + x])
                    if (below && !spanBelow) {
                        stack.addLast((y + 1) * w + x)
                        spanBelow = true
                    } else if (!below) {
                        spanBelow = false
                    }
                }
                x++
            }
        }
    }
}
