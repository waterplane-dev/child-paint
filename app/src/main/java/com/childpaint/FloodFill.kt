package com.childpaint

import android.graphics.Bitmap
import android.graphics.Color
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

    fun fill(bitmap: Bitmap, startX: Int, startY: Int, newColor: Int, tolerance: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (startX < 0 || startX >= w || startY < 0 || startY >= h) return

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val target = pixels[startY * w + startX]
        if (target == newColor) return

        // Filled pixels take newColor, and newColor never matches, so each pixel is
        // visited once and the scan always terminates.
        fun matches(p: Int): Boolean {
            if (p == newColor) return false
            if (p == target) return true
            val d = max(
                max(abs(Color.red(p) - Color.red(target)), abs(Color.green(p) - Color.green(target))),
                max(abs(Color.blue(p) - Color.blue(target)), abs(Color.alpha(p) - Color.alpha(target)))
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

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
