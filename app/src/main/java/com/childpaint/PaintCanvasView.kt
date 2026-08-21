package com.childpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The paper. Everything is composited into a single opaque [Bitmap] so that the
 * paint-bucket has real pixels to flood into; the op list on top of it is what
 * makes undo/redo possible without keeping bitmap snapshots around.
 */
class PaintCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Called when the TEXT tool is tapped, with the tap point in canvas pixels. */
    var onTextRequested: ((x: Float, y: Float) -> Unit)? = null

    /** Called whenever the undo/redo availability changes. */
    var onHistoryChanged: (() -> Unit)? = null

    var paperColor: Int = Color.WHITE

    var tool: Tool = Tool.BRUSH
    var color: Int = Color.BLACK
    var strokeWidth: Float = 18f
    var fillShapes: Boolean = false

    /** Text is sized off the brush slider so one control drives both. */
    val textSize: Float get() = strokeWidth * 4f

    private val ops = mutableListOf<DrawOp>()
    private val redoStack = mutableListOf<DrawOp>()

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    /**
     * A picture restored from a previous session. It sits underneath the op list as
     * the starting paper, so undo still walks back through this session's ops and
     * stops at the restored image rather than at a blank page.
     */
    private var basePicture: Bitmap? = null

    /**
     * Bitmap snapshots taken every [CHECKPOINT_INTERVAL] committed ops, so a replay
     * only has to re-run a bounded suffix of the history.
     *
     * The op list keeps memory flat, but replay cost grows with it, and a [FillOp]
     * is not cheap to re-run: it walks every pixel of the bitmap. A picture with a
     * dozen fills in it made undo a visible freeze. Two snapshots (~8 MB apiece)
     * are kept so undoing back past the newest one still lands on a snapshot.
     */
    private class Checkpoint(val opCount: Int, val bitmap: Bitmap)

    private val checkpoints = mutableListOf<Checkpoint>()

    // In-progress gesture, drawn as a preview over the bitmap until the finger lifts.
    private var activePath: Path? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    val canUndo: Boolean get() = ops.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap = bmp
        bitmapCanvas = Canvas(bmp)
        dropCheckpointsAfter(0)
        replayAll()
    }

    private fun replayAll() {
        val bmp = bitmap ?: return
        val canvas = bitmapCanvas ?: return
        val from = checkpoints.lastOrNull()
        if (from != null) {
            canvas.drawBitmap(from.bitmap, 0f, 0f, null)
        } else {
            canvas.drawColor(paperColor)
            basePicture?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null) }
        }
        for (i in (from?.opCount ?: 0) until ops.size) ops[i].apply(canvas, bmp)
        invalidate()
    }

    /** Snapshots the bitmap once enough ops have piled up since the last snapshot. */
    private fun maybeCheckpoint() {
        val bmp = bitmap ?: return
        val since = ops.size - (checkpoints.lastOrNull()?.opCount ?: 0)
        if (since < CHECKPOINT_INTERVAL) return
        checkpoints += Checkpoint(ops.size, bmp.copy(Bitmap.Config.ARGB_8888, false))
        while (checkpoints.size > MAX_CHECKPOINTS) {
            checkpoints.removeAt(0).bitmap.recycle()
        }
    }

    /** Undo invalidates every snapshot taken past the new end of the op list. */
    private fun dropCheckpointsAfter(opCount: Int) {
        while (checkpoints.isNotEmpty() && checkpoints.last().opCount > opCount) {
            checkpoints.removeAt(checkpoints.lastIndex).bitmap.recycle()
        }
    }

    /** Puts a saved picture back on the paper; safe to call before the view is laid out. */
    fun restorePicture(picture: Bitmap) {
        basePicture = picture
        dropCheckpointsAfter(0)
        replayAll()
    }

    private fun commit(op: DrawOp) {
        val bmp = bitmap ?: return
        val canvas = bitmapCanvas ?: return
        ops.add(op)
        redoStack.clear()
        op.apply(canvas, bmp)
        maybeCheckpoint()
        invalidate()
        onHistoryChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Live preview of the gesture in flight.
        if (!dragging) return
        val activeColor = if (tool == Tool.ERASER) paperColor else color
        activePath?.let { canvas.drawPath(it, strokePaint(activeColor, strokeWidth)) }
        tool.asShape()?.let { kind ->
            drawShape(canvas, kind, downX, downY, lastX, lastY, activeColor, strokeWidth, fillShapes)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointerId = event.getPointerId(0)
                val x = event.x
                val y = event.y
                downX = x; downY = y; lastX = x; lastY = y
                dragging = true
                when (tool) {
                    Tool.BRUSH, Tool.ERASER -> activePath = Path().apply { moveTo(x, y) }
                    else -> activePath = null
                }
                invalidate()
                return true
            }

            // A second finger — usually the palm of the hand holding the phone — must
            // not steal the stroke. Only the pointer that started the gesture draws.
            MotionEvent.ACTION_POINTER_DOWN -> return true

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                when (tool) {
                    Tool.BRUSH, Tool.ERASER -> {
                        // Quadratic smoothing through the midpoint keeps freehand lines
                        // from looking like a chain of straight segments.
                        activePath?.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
                    }
                    else -> Unit
                }
                lastX = x; lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // The drawing finger lifted while another is still down. End the gesture
                // here: without this the stroke stayed live and the next MOVE snapped it
                // across the paper to whichever pointer became index 0.
                if (event.getPointerId(event.actionIndex) != activePointerId) return true
                endGesture(event.getX(event.actionIndex), event.getY(event.actionIndex))
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) cancelGesture() else endGesture(event.getX(index), event.getY(index))
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun endGesture(x: Float, y: Float) {
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        finishGesture(x, y)
    }

    private fun cancelGesture() {
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        activePath = null
        invalidate()
    }

    private fun finishGesture(x: Float, y: Float) {
        lastX = x; lastY = y
        val tappedInPlace = abs(x - downX) < TAP_SLOP && abs(y - downY) < TAP_SLOP

        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                val path = activePath ?: return
                if (tappedInPlace) {
                    // A tap should leave a dot, not nothing.
                    path.lineTo(x, y + 0.1f)
                } else {
                    path.lineTo(x, y)
                }
                activePath = null
                commit(StrokeOp(path, if (tool == Tool.ERASER) paperColor else color, strokeWidth))
            }

            Tool.FILL -> commit(FillOp(x.toInt(), y.toInt(), color, FloodFill.DEFAULT_TOLERANCE))

            Tool.TEXT -> {
                invalidate()
                onTextRequested?.invoke(x, y)
            }

            else -> {
                val kind = tool.asShape() ?: return
                // Ignore accidental zero-size shapes from a stray tap.
                if (tappedInPlace) {
                    invalidate()
                    return
                }
                commit(ShapeOp(kind, downX, downY, x, y, color, strokeWidth, fillShapes))
            }
        }
    }

    fun addText(text: String, x: Float, y: Float) {
        if (text.isBlank()) return
        commit(TextOp(text, x, y, color, textSize))
    }

    fun undo() {
        if (ops.isEmpty()) return
        redoStack.add(ops.removeAt(ops.lastIndex))
        dropCheckpointsAfter(ops.size)
        replayAll()
        onHistoryChanged?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val op = redoStack.removeAt(redoStack.lastIndex)
        ops.add(op)
        val bmp = bitmap ?: return
        val canvas = bitmapCanvas ?: return
        op.apply(canvas, bmp)
        maybeCheckpoint()
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** Clear is itself an op, so it can be undone like anything else. */
    fun clearPaper() {
        commit(ClearOp(paperColor))
    }

    /** A copy, so the caller can hand it to an encoder without racing the canvas. */
    fun exportBitmap(): Bitmap? = bitmap?.copy(Bitmap.Config.ARGB_8888, false)

    private companion object {
        const val TAP_SLOP = 6f
        const val CHECKPOINT_INTERVAL = 12
        const val MAX_CHECKPOINTS = 2
    }
}
