package com.childpaint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Writes the op list to JSON and back, so a picture survives a restart as the
 * separate things it was drawn as rather than as one flat image.
 *
 * Flat was fine while nothing could be picked up after it settled. Once the hand
 * can lift a heart drawn yesterday, a kill — the one exit kid mode leaves open —
 * must not quietly turn every heart into pixels.
 *
 * Compatibility rules, because once this ships the files outlive the code that
 * wrote them:
 * - A new kind of op gets a new `t`; an existing op is never reshaped. Older
 *   versions read an op they do not know as an [UnknownOp] and keep the rest.
 * - A new field on an existing op is optional, read with a default (`opt*`), so
 *   files written before it existed still load.
 * - [VERSION] goes up only for a change old readers would get *wrong*, not merely
 *   not understand. A file from a newer version is refused (the flat picture stands
 *   in); one from an older version is read, migrating in [decode] if ever needed.
 */
object OpCodec {

    /** A saved session: the op list, the paper size it was drawn at, and which photo was under it. */
    class Session(val width: Int, val height: Int, val paperId: String?, val ops: List<DrawOp>)

    private const val VERSION = 1

    fun encode(session: Session): String = JSONObject().apply {
        put("v", VERSION)
        put("w", session.width)
        put("h", session.height)
        put("paper", session.paperId ?: JSONObject.NULL)
        put("ops", JSONArray().apply { session.ops.forEach { put(encodeOp(it)) } })
    }.toString()

    /** Null for anything unreadable; the caller falls back to the flat picture. */
    fun decode(json: String): Session? = try {
        val o = JSONObject(json)
        if (o.getInt("v") > VERSION) null
        else {
            val array = o.getJSONArray("ops")
            Session(
                o.getInt("w"), o.getInt("h"),
                if (o.isNull("paper")) null else o.getString("paper"),
                (0 until array.length()).map { decodeOp(array.getJSONObject(it)) }
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun encodeOp(op: DrawOp): JSONObject = if (op is UnknownOp) JSONObject(op.json) else JSONObject().apply {
        when (op) {
            is UnknownOp -> Unit
            is StrokeOp -> {
                put("t", "stroke")
                put("pts", points(op.points))
                put("c", op.color)
                put("w", op.width.toDouble())
                put("e", op.eraser)
            }
            is ShapeOp -> {
                put("t", "shape")
                put("k", op.kind.name)
                put("r", floats(op.x0, op.y0, op.x1, op.y1))
                put("c", op.color)
                put("w", op.width.toDouble())
                put("s", op.style.name)
                put("a", op.angle.toDouble())
            }
            is TextOp -> {
                put("t", "text")
                put("x", op.text)
                put("r", rect(op.box))
                put("c", op.color)
                put("z", op.size.toDouble())
                put("a", op.angle.toDouble())
            }
            is FillOp -> {
                put("t", "fill")
                put("p", JSONArray().put(op.x).put(op.y))
                put("c", op.color)
                put("tol", op.tolerance)
            }
            is MoveOp -> {
                put("t", "move")
                put("src", JSONArray().put(op.src.left).put(op.src.top).put(op.src.right).put(op.src.bottom))
                put("dst", rect(op.dst))
                put("a", op.angle.toDouble())
                put("c", op.paperColor)
            }
            is EraseOp -> {
                put("t", "erase")
                put("r", JSONArray().put(op.rect.left).put(op.rect.top).put(op.rect.right).put(op.rect.bottom))
                put("c", op.paperColor)
            }
            is ClearOp -> {
                put("t", "clear")
                put("c", op.color)
            }
            is GroupOp -> {
                put("t", "group")
                put("m", JSONArray().apply { op.members.forEach { put(encodeOp(it)) } })
                put("f", JSONArray().put(op.frame.left).put(op.frame.top).put(op.frame.right).put(op.frame.bottom))
                put("dst", rect(op.dst))
                put("a", op.angle.toDouble())
            }
            is RelocateOp -> {
                put("t", "relocate")
                put("h", JSONArray().apply { op.hidden.forEach { put(it) } })
                op.content?.let { put("in", encodeOp(it)) }
            }
        }
    }

    /**
     * One op, or an [UnknownOp] holding its JSON when it cannot be read — a kind or
     * shape this version does not know. One unreadable op must not cost the whole
     * picture, and it must keep its place in the list: see [UnknownOp].
     */
    private fun decodeOp(o: JSONObject): DrawOp = try {
        decodeKnownOp(o)
    } catch (e: Exception) {
        UnknownOp(o.toString())
    }

    private fun decodeKnownOp(o: JSONObject): DrawOp = when (o.getString("t")) {
        "stroke" -> StrokeOp(
            parsePoints(o.getString("pts")), o.getInt("c"), o.getDouble("w").toFloat(), o.optBoolean("e")
        )
        "shape" -> {
            val r = o.getJSONArray("r")
            ShapeOp(
                ShapeKind.valueOf(o.getString("k")),
                r.f(0), r.f(1), r.f(2), r.f(3),
                o.getInt("c"), o.getDouble("w").toFloat(),
                ShapeStyle.valueOf(o.getString("s")), o.getDouble("a").toFloat()
            )
        }
        "text" -> TextOp(
            o.getString("x"), rectF(o.getJSONArray("r")), o.getInt("c"),
            o.getDouble("z").toFloat(), o.getDouble("a").toFloat()
        )
        "fill" -> {
            val p = o.getJSONArray("p")
            FillOp(p.getInt(0), p.getInt(1), o.getInt("c"), o.getInt("tol"))
        }
        "move" -> {
            val s = o.getJSONArray("src")
            MoveOp(
                Rect(s.getInt(0), s.getInt(1), s.getInt(2), s.getInt(3)),
                rectF(o.getJSONArray("dst")), o.getDouble("a").toFloat(), o.getInt("c")
            )
        }
        "erase" -> {
            val r = o.getJSONArray("r")
            EraseOp(Rect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3)), o.getInt("c"))
        }
        "clear" -> ClearOp(o.getInt("c"))
        "group" -> {
            val m = o.getJSONArray("m")
            val f = o.getJSONArray("f")
            GroupOp(
                (0 until m.length()).map { decodeOp(m.getJSONObject(it)) },
                Rect(f.getInt(0), f.getInt(1), f.getInt(2), f.getInt(3)),
                rectF(o.getJSONArray("dst")), o.getDouble("a").toFloat()
            )
        }
        "relocate" -> {
            val h = o.getJSONArray("h")
            RelocateOp(
                IntArray(h.length()) { h.getInt(it) },
                if (o.has("in")) decodeOp(o.getJSONObject("in")) else null
            )
        }
        else -> throw IllegalArgumentException("unknown op ${o.getString("t")}")
    }

    /** Points as one "x,y,x,y" string at a tenth of a pixel — a long scribble is thousands of them. */
    private fun points(values: FloatArray): String =
        values.joinToString(",") { String.format(Locale.ROOT, "%.1f", it) }

    private fun parsePoints(s: String): FloatArray =
        if (s.isEmpty()) FloatArray(0) else s.split(',').map { it.toFloat() }.toFloatArray()

    private fun floats(vararg v: Float) = JSONArray().apply { v.forEach { put(it.toDouble()) } }

    private fun rect(r: RectF) = floats(r.left, r.top, r.right, r.bottom)

    private fun rectF(a: JSONArray) = RectF(a.f(0), a.f(1), a.f(2), a.f(3))

    private fun JSONArray.f(i: Int) = getDouble(i).toFloat()
}

/**
 * An op this version cannot read, most likely written by a newer one. It paints
 * nothing and cannot be picked up, but it keeps its slot: [RelocateOp.hidden]
 * refers to ops by position, so dropping it would shift every later index onto the
 * wrong thing. Its JSON is written back exactly as it came, so a round trip through
 * an older version loses nothing.
 */
class UnknownOp(val json: String) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) = Unit
}
