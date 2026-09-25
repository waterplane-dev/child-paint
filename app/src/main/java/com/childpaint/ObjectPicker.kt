package com.childpaint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Finds the thing under a finger in the op list and works out what has to come
 * with it when it is picked up.
 *
 * A child who paints a face on a circle and then moves the circle expects the
 * face to come along — it is a paper cut-out to them, not a stack of layers. So a
 * pick is the tapped op plus every later op that sits on it: strokes, shapes and
 * text mostly inside it, and bucket fills poured into it.
 */
object ObjectPicker {

    /**
     * What was picked up. [hidden] are the op indices that leave the paper while it
     * is held; [members] is what gets carried, in drawing order. [layer] is only
     * built when more than one thing is carried — a single shape or text stays
     * vector so it can be stretched cleanly.
     */
    class Pick(
        val hidden: IntArray,
        val members: List<DrawOp>,
        val frame: Rect?,
        val layer: Bitmap?
    )

    /** Every op index some later [RelocateOp] has taken off the paper. */
    fun hiddenIn(ops: List<DrawOp>): MutableSet<Int> {
        val hidden = HashSet<Int>()
        for (op in ops) if (op is RelocateOp) op.hidden.forEach { hidden += it }
        return hidden
    }

    /**
     * The topmost pickable op under ([x], [y]), or null. An exact hit wins over a
     * near one, so a small thing lying on a big one can still be reached; only if
     * nothing is directly under the finger is a ring of [slop] around it tried.
     */
    fun find(ops: List<DrawOp>, hidden: Set<Int>, x: Float, y: Float, slop: Float): Int? {
        findAt(ops, hidden, listOf(floatArrayOf(x, y)))?.let { return it }
        val ring = (0 until 8).map { k ->
            val a = Math.PI * 2 * k / 8
            floatArrayOf(x + slop * cos(a).toFloat(), y + slop * sin(a).toFloat())
        }
        return findAt(ops, hidden, ring)
    }

    private fun findAt(ops: List<DrawOp>, hidden: Set<Int>, points: List<FloatArray>): Int? {
        for (i in ops.indices.reversed()) {
            if (i in hidden) continue
            when (val op = ops[i]) {
                // Everything underneath a clear is gone.
                is ClearOp -> return null
                // A moved or erased rectangle is pixels, not a thing; whatever it
                // covers is no longer what the op list says is there.
                is MoveOp, is EraseOp ->
                    if (points.any { op.bounds()!!.contains(it[0], it[1]) }) return null
                else -> {
                    val content = pickable(op) ?: continue
                    if (points.any { content.hits(it[0], it[1]) }) return i
                }
            }
        }
        return null
    }

    /** The thing an op puts on the paper, if it is something a hand can hold. */
    private fun pickable(op: DrawOp): DrawOp? = when (val content = contentOf(op)) {
        is StrokeOp -> content.takeUnless { it.eraser }
        is ShapeOp, is TextOp, is GroupOp -> content
        else -> null
    }

    fun contentOf(op: DrawOp): DrawOp? = if (op is RelocateOp) op.content else op

    /**
     * Picks up op [index] and whatever sits on it, or returns null when it cannot be
     * lifted cleanly — a later rectangle move over it reads its pixels, and taking
     * the thing away underneath would change what that move carried; a later erase
     * over part of it would be left behind as a paper-coloured patch.
     */
    fun pick(ops: List<DrawOp>, hidden: Set<Int>, index: Int, paperWidth: Int, paperHeight: Int): Pick? {
        val anchor = pickable(ops[index]) ?: return null
        val anchorBounds = anchor.bounds() ?: return null
        val anchorArea = area(anchorBounds)

        val carried = mutableListOf<Int>()
        val fills = mutableListOf<Int>()
        for (j in index + 1 until ops.size) {
            if (j in hidden) continue
            val op = ops[j]
            if (op is ClearOp) return null
            if (op is MoveOp || op is EraseOp) continue
            val content = contentOf(op) ?: continue
            if (content is FillOp) {
                if (anchorBounds.contains(content.x.toFloat(), content.y.toFloat())) fills += j
                continue
            }
            val b = content.bounds() ?: continue
            if (area(b) <= anchorArea && overlap(b, anchorBounds) >= CARRY_SHARE * area(b)) carried += j
        }

        val frameF = RectF(anchorBounds)
        for (j in carried) contentOf(ops[j])?.bounds()?.let { frameF.union(it) }
        frameF.inset(-PAD, -PAD)
        val frame = Rect()
        frameF.roundOut(frame)
        if (!frame.intersect(-PAD.toInt(), -PAD.toInt(), paperWidth + PAD.toInt(), paperHeight + PAD.toInt())) return null

        // A later rectangle move that touches any of it would replay differently.
        for (j in index + 1 until ops.size) {
            val op = ops[j]
            if (j !in hidden && (op is MoveOp || op is EraseOp) && RectF.intersects(op.bounds()!!, frameF)) return null
        }

        if (carried.isEmpty() && fills.isEmpty()) {
            return Pick(intArrayOf(index), listOf(anchor), null, null)
        }

        // Build the layer in drawing order. Each candidate fill is tried and kept
        // only if it stays inside the carried things: one that runs out to the edge
        // of the layer was a background fill that merely started near the object,
        // and carrying it would carry a rectangle of background along.
        val order = (listOf(index) + carried + fills).sorted()
        val layer = Bitmap.createBitmap(frame.width(), frame.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(layer)
        canvas.translate(-frame.left.toFloat(), -frame.top.toFloat())
        val members = mutableListOf<DrawOp>()
        val taken = mutableListOf<Int>()
        for (j in order) {
            val member = contentOf(ops[j]) ?: continue
            if (member is FillOp) {
                val before = layer.copy(Bitmap.Config.ARGB_8888, true)
                member.applyAt(layer, frame.left, frame.top)
                if (touchesEdge(layer, before)) {
                    // Put the layer back as it was before the escaped fill.
                    Canvas(layer).drawBitmap(before, 0f, 0f, restorePaint)
                    before.recycle()
                    continue
                }
                before.recycle()
            } else {
                drawMember(member, canvas, layer, frame)
            }
            members += member
            taken += j
        }
        if (members.size == 1) {
            layer.recycle()
            return Pick(intArrayOf(index), listOf(anchor), null, null)
        }
        return Pick(taken.toIntArray(), members, frame, layer)
    }

    /** True if any pixel on the outermost ring changed — the fill got out. */
    private fun touchesEdge(after: Bitmap, before: Bitmap): Boolean {
        val w = after.width
        val h = after.height
        for (x in 0 until w) {
            if (after.getPixel(x, 0) != before.getPixel(x, 0)) return true
            if (after.getPixel(x, h - 1) != before.getPixel(x, h - 1)) return true
        }
        for (y in 0 until h) {
            if (after.getPixel(0, y) != before.getPixel(0, y)) return true
            if (after.getPixel(w - 1, y) != before.getPixel(w - 1, y)) return true
        }
        return false
    }

    private val restorePaint = android.graphics.Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
    }

    private fun area(r: RectF) = r.width().coerceAtLeast(0f) * r.height().coerceAtLeast(0f)

    private fun overlap(a: RectF, b: RectF): Float {
        val r = RectF()
        return if (r.setIntersect(a, b)) area(r) else 0f
    }

    /** How much of a later thing has to lie on the picked one for it to come along. */
    private const val CARRY_SHARE = 0.75f

    /** Transparent margin round the layer, so an escaping fill has an edge to hit. */
    private const val PAD = 3f
}
