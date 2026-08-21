package com.childpaint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Which tool the user currently has selected. */
enum class Tool {
    BRUSH, ERASER, FILL, TEXT,
    LINE, RECT, OVAL, TRIANGLE, STAR, HEART
}

/** Shape tools that are drawn from a drag rectangle. */
enum class ShapeKind { LINE, RECT, OVAL, TRIANGLE, STAR, HEART }

fun Tool.asShape(): ShapeKind? = when (this) {
    Tool.LINE -> ShapeKind.LINE
    Tool.RECT -> ShapeKind.RECT
    Tool.OVAL -> ShapeKind.OVAL
    Tool.TRIANGLE -> ShapeKind.TRIANGLE
    Tool.STAR -> ShapeKind.STAR
    Tool.HEART -> ShapeKind.HEART
    else -> null
}

/**
 * One user action. The canvas keeps a list of these instead of a stack of bitmap
 * snapshots: undo replays the list onto a blank bitmap, which costs a few ms but
 * keeps memory flat (a full-screen ARGB snapshot is ~8 MB apiece).
 */
sealed class DrawOp {
    abstract fun apply(canvas: Canvas, bitmap: Bitmap)
}

class StrokeOp(
    val path: Path,
    private val color: Int,
    private val width: Float
) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawPath(path, strokePaint(color, width))
    }
}

class ShapeOp(
    private val kind: ShapeKind,
    private val x0: Float,
    private val y0: Float,
    private val x1: Float,
    private val y1: Float,
    private val color: Int,
    private val width: Float,
    private val filled: Boolean
) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        drawShape(canvas, kind, x0, y0, x1, y1, color, width, filled)
    }
}

class TextOp(
    private val text: String,
    private val x: Float,
    private val y: Float,
    private val color: Int,
    private val size: Float
) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@TextOp.color
            textSize = size
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(text, x, y, paint)
    }
}

class FillOp(
    private val x: Int,
    private val y: Int,
    private val color: Int,
    private val tolerance: Int
) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        FloodFill.fill(bitmap, x, y, color, tolerance)
    }
}

class ClearOp(private val color: Int) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(color)
    }
}

fun strokePaint(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

fun drawShape(
    canvas: Canvas,
    kind: ShapeKind,
    x0: Float, y0: Float, x1: Float, y1: Float,
    color: Int, width: Float, filled: Boolean
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = if (filled && kind != ShapeKind.LINE) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val rect = RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    when (kind) {
        ShapeKind.LINE -> canvas.drawLine(x0, y0, x1, y1, paint)
        ShapeKind.RECT -> canvas.drawRect(rect, paint)
        ShapeKind.OVAL -> canvas.drawOval(rect, paint)
        ShapeKind.TRIANGLE -> canvas.drawPath(trianglePath(rect), paint)
        ShapeKind.STAR -> canvas.drawPath(starPath(rect), paint)
        ShapeKind.HEART -> canvas.drawPath(heartPath(rect), paint)
    }
}

private fun trianglePath(r: RectF) = Path().apply {
    moveTo(r.centerX(), r.top)
    lineTo(r.right, r.bottom)
    lineTo(r.left, r.bottom)
    close()
}

private fun starPath(r: RectF, points: Int = 5): Path {
    val cx = r.centerX()
    val cy = r.centerY()
    val outer = min(r.width(), r.height()) / 2f
    val inner = outer * 0.42f
    val path = Path()
    // Start at the top point and alternate outer/inner radius.
    val step = Math.PI / points
    var angle = -Math.PI / 2
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) outer else inner
        val px = cx + (radius * cos(angle)).toFloat()
        val py = cy + (radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        angle += step
    }
    path.close()
    return path
}

private fun heartPath(r: RectF): Path {
    val w = r.width()
    val h = r.height()
    return Path().apply {
        moveTo(r.left + w / 2f, r.top + h)
        cubicTo(
            r.left - w * 0.2f, r.top + h * 0.45f,
            r.left + w * 0.2f, r.top - h * 0.1f,
            r.left + w / 2f, r.top + h * 0.28f
        )
        cubicTo(
            r.left + w * 0.8f, r.top - h * 0.1f,
            r.left + w * 1.2f, r.top + h * 0.45f,
            r.left + w / 2f, r.top + h
        )
        close()
    }
}
