package com.childpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The paper. Everything is composited into a single opaque [Bitmap] so that the
 * paint-bucket has real pixels to flood into; the op list on top of it is what
 * makes undo/redo possible without keeping bitmap snapshots around.
 */
class PaintCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Called when text is wanted, with whatever the box already says — empty for a
     * new box, the current text when an existing one was tapped to be edited. The
     * box itself is held here until [addText] or [cancelText] answers.
     */
    var onTextRequested: ((existing: String) -> Unit)? = null

    /** Called on every touch that lands on the paper, before it is acted on. */
    var onCanvasTouched: (() -> Unit)? = null

    /** Called whenever the undo/redo availability changes. */
    var onHistoryChanged: (() -> Unit)? = null

    var paperColor: Int = Color.WHITE

    /** Changing tool settles whatever shape is still being adjusted. */
    var tool: Tool = Tool.BRUSH
        set(value) {
            if (field != value) commitLive()
            field = value
        }

    // Colour, size and style follow the shape that is still live, so a child can
    // place a hat and then decide it should be red without starting over.
    var color: Int = Color.BLACK
        set(value) {
            field = value
            liveShape?.color = value
            liveText?.color = value
            invalidate()
        }

    var strokeWidth: Float = 18f
        set(value) {
            field = value
            liveShape?.width = value
            // The slider is the font control: it retypes the live text at the new
            // size, which rewraps it, which is the whole point of a box.
            liveText?.let {
                it.size = value * TEXT_SIZE_FACTOR
                it.reflow()
            }
            invalidate()
        }

    /** Filled by default: an outline a child then has to aim the bucket into is a
     *  second step nobody asked for. */
    var shapeStyle: ShapeStyle = ShapeStyle.FILLED
        set(value) {
            field = value
            liveShape?.style = value
            invalidate()
        }

    /** Text is sized off the brush slider so one control drives both. */
    val textSize: Float get() = strokeWidth * TEXT_SIZE_FACTOR

    /** The box the pending text will land in, held while the dialog is up. */
    private var pendingTextBox: RectF? = null

    /** Set when the dialog was opened to edit existing text rather than add new. */
    private var editingText: LiveText? = null

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

    /**
     * Something that has been put on the paper but not yet committed to the
     * bitmap: a shape that was just drawn, or a rectangle of pixels just lifted
     * off the picture.
     *
     * Everything else in this view is final the moment the finger lifts, which is
     * fine for a brush stroke — you drew where you dragged — but wrong for a
     * shape: a moustache lands in the right place only by luck. So the thing that
     * was just placed stays live and adjustable, and only settles when the child
     * does something else. Because it is not in the bitmap, moving it costs one
     * redraw and no replay at all.
     */
    private abstract class Live(
        var x0: Float, var y0: Float, var x1: Float, var y1: Float,
        var angle: Float
    ) {
        val centerX: Float get() = (x0 + x1) / 2f
        val centerY: Float get() = (y0 + y1) / 2f

        fun translate(dx: Float, dy: Float) {
            x0 += dx; x1 += dx; y0 += dy; y1 += dy
        }

        /** Scales about the centre, so a pinch grows the shape where it already is. */
        open fun scaleBy(factor: Float) {
            val cx = centerX
            val cy = centerY
            x0 = cx + (x0 - cx) * factor
            x1 = cx + (x1 - cx) * factor
            y0 = cy + (y0 - cy) * factor
            y1 = cy + (y1 - cy) * factor
        }

        /**
         * The touch point moved into the object's own upright frame — the touch is
         * rotated back rather than the box being rotated forward, because every
         * box here is stored upright with an angle beside it.
         */
        fun toLocal(px: Float, py: Float): PointF {
            val cx = centerX
            val cy = centerY
            val radians = Math.toRadians(-angle.toDouble())
            val dx = px - cx
            val dy = py - cy
            return PointF(
                cx + (dx * cos(radians) - dy * sin(radians)).toFloat(),
                cy + (dx * sin(radians) + dy * cos(radians)).toFloat()
            )
        }

        fun contains(px: Float, py: Float, margin: Float): Boolean {
            val local = toLocal(px, py)
            return local.x >= minOf(x0, x1) - margin && local.x <= maxOf(x0, x1) + margin &&
                local.y >= minOf(y0, y1) - margin && local.y <= maxOf(y0, y1) + margin
        }

        /** The upright box the live thing currently occupies. */
        fun box() = RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))

        /** The op that settles this into the bitmap, or null if there is nothing to record. */
        abstract fun toOp(): DrawOp?
    }

    private class LiveShape(
        val kind: ShapeKind,
        x0: Float, y0: Float, x1: Float, y1: Float,
        var color: Int,
        var width: Float,
        var style: ShapeStyle
    ) : Live(x0, y0, x1, y1, 0f) {
        override fun toOp() = ShapeOp(kind, x0, y0, x1, y1, color, width, style, angle)
    }

    /**
     * A rectangle of pixels lifted off the picture and floating above it.
     *
     * The pixels are still in the bitmap underneath — nothing is cut until the
     * selection settles — so the hole is painted at draw time and undoing is just
     * a matter of dropping the selection and letting the paper show through again.
     */
    private class LiveSelection(
        val src: Rect,
        val piece: Bitmap,
        val paperColor: Int
    ) : Live(
        src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat(), 0f
    ) {
        /** Selecting and then thinking better of it must not land an op in history. */
        private val moved: Boolean
            get() = angle != 0f || box().let {
                abs(it.left - src.left) > 0.5f || abs(it.top - src.top) > 0.5f ||
                    abs(it.right - src.right) > 0.5f || abs(it.bottom - src.bottom) > 0.5f
            }

        override fun toOp(): DrawOp? =
            if (moved) MoveOp(Rect(src), box(), angle, paperColor) else null
    }

    /**
     * Text that has been typed but not yet committed, laid out inside a box.
     *
     * The box width is the wrap width, so dragging the corner handle rewraps the
     * text instead of stretching it, and the height is never an input: it is
     * whatever the wrapped text turned out to be, recomputed by [reflow] after
     * every change. Font size is separate — the size slider drives it — which is
     * what makes "smaller letters" and "narrower column" two different moves.
     */
    private class LiveText(
        var text: String,
        x0: Float, y0: Float, x1: Float, y1: Float,
        var color: Int,
        var size: Float
    ) : Live(x0, y0, x1, y1, 0f) {
        /** A pinch scales the lettering with the column, or the text would rewrap mid-pinch. */
        override fun scaleBy(factor: Float) {
            super.scaleBy(factor)
            size *= factor
            reflow()
        }

        /** Normalises the box and drops the bottom edge onto the wrapped text. */
        fun reflow() {
            val left = minOf(x0, x1)
            val width = maxOf(abs(x1 - x0), MIN_TEXT_WIDTH)
            val top = minOf(y0, y1)
            x0 = left
            x1 = left + width
            y0 = top
            y1 = top + textHeight(text, width, size)
        }

        /** The grab circle sits on the bottom-right corner, in the box's own frame. */
        fun onHandle(px: Float, py: Float, radius: Float): Boolean {
            val local = toLocal(px, py)
            return hypot(local.x - maxOf(x0, x1), local.y - maxOf(y0, y1)) <= radius
        }

        override fun toOp(): DrawOp? =
            if (text.isBlank()) null else TextOp(text, box(), color, size, angle)
    }

    /** The shape or selection still floating above the bitmap, if there is one. */
    private var live: Live? = null
    private val liveShape: LiveShape? get() = live as? LiveShape
    private val liveSelection: LiveSelection? get() = live as? LiveSelection
    private val liveText: LiveText? get() = live as? LiveText

    /** True while the gesture in flight is moving the live shape, not drawing. */
    private var adjusting = false

    /** True while the gesture is dragging the text box's corner handle. */
    private var resizing = false
    private var pinching = false
    private var pinchDistance = 0f
    private var pinchAngle = 0f

    // Two strokes, not one: a pale solid line underneath and dark dashes on top.
    // A single translucent grey box vanished into a dark scribble, which is
    // exactly the picture a child is most likely to be moving something around on.
    private val liveOutlineHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6FFFFFF.toInt()
    }

    private val liveOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xDD3A2A1C.toInt()
        pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
    }

    // In-progress gesture, drawn as a preview over the bitmap until the finger lifts.
    private var activePath: Path? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    val canUndo: Boolean get() = ops.isNotEmpty() || live != null
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // Op coordinates are canvas pixels, so settle whatever is floating before
        // the paper changes size under it.
        commitLive()
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
            // Scaled to fit rather than stretched to fill. In the normal case the
            // picture is already exactly canvas-sized and this is the identity; it
            // matters when the canvas has since changed height, where stretching
            // would squash a face to fit the new shape.
            basePicture?.let { canvas.drawBitmap(it, null, fitRect(it), null) }
        }
        for (i in (from?.opCount ?: 0) until ops.size) ops[i].apply(canvas, bmp)
        invalidate()
    }

    private fun fitRect(picture: Bitmap): RectF {
        val scale = minOf(width / picture.width.toFloat(), height / picture.height.toFloat())
        val w = picture.width * scale
        val h = picture.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
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

    /**
     * Replaces the paper with [picture] and starts a fresh history on top of it.
     *
     * Unlike [restorePicture] this drops the op list: the new paper is a new
     * picture, and leaving the old ops in place would replay a child's drawing on
     * top of a photo they have never seen.
     */
    fun setPaperPicture(picture: Bitmap) {
        live = null
        ops.clear()
        redoStack.clear()
        dropCheckpointsAfter(0)
        basePicture = picture
        replayAll()
        onHistoryChanged?.invoke()
    }

    /**
     * Throws the paper away — a photo with it — and starts a blank page.
     *
     * Clearing paints over a photo but leaves it installed underneath, so undo
     * walks back onto it. That is the right behaviour for the clear button and the
     * wrong one for "I am done with this photo", which is what this is for.
     */
    fun useBlankPaper() {
        live = null
        ops.clear()
        redoStack.clear()
        dropCheckpointsAfter(0)
        basePicture = null
        replayAll()
        onHistoryChanged?.invoke()
    }

    /** Puts a saved picture back on the paper; safe to call before the view is laid out. */
    fun restorePicture(picture: Bitmap) {
        basePicture = picture
        dropCheckpointsAfter(0)
        replayAll()
    }

    /**
     * Settles the live shape or selection into the bitmap. Called by anything that
     * reads or rewrites history — undo, clear, export, a new gesture somewhere
     * else — so nothing can be left hovering outside the picture it belongs to.
     */
    fun commitLive() {
        val current = live ?: return
        live = null
        val op = current.toOp()
        if (op == null) {
            // A selection that was never moved. Nothing happened, so nothing is
            // recorded — but the marching-ants box has to come off the screen.
            invalidate()
            onHistoryChanged?.invoke()
            return
        }
        commit(op)
    }

    private fun discardLive(): Boolean {
        if (live == null) return false
        live = null
        invalidate()
        onHistoryChanged?.invoke()
        return true
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

        live?.let {
            drawLive(canvas, it)
            drawLiveMarker(canvas, it)
        }

        // Live preview of the gesture in flight.
        if (!dragging) return
        val activeColor = if (tool == Tool.ERASER) paperColor else color
        activePath?.let { canvas.drawPath(it, strokePaint(activeColor, strokeWidth)) }
        tool.asShape()?.let { kind ->
            drawShape(canvas, kind, downX, downY, lastX, lastY, activeColor, strokeWidth, shapeStyle)
        }
        // The box being dragged out. These two are the tools whose preview is the
        // box itself rather than the thing the box will hold — the selection is
        // whatever the box lands on, the text is whatever the box is asked for.
        if (tool == Tool.SELECT || tool == Tool.TEXT) {
            drawMarquee(canvas, RectF(minOf(downX, lastX), minOf(downY, lastY), maxOf(downX, lastX), maxOf(downY, lastY)))
        }
    }

    /**
     * Paints whatever is floating above the bitmap. A selection also has to paint
     * its own hole: the pixels it lifted are still in the bitmap underneath and
     * would otherwise show through as a copy left behind.
     */
    private fun drawLive(canvas: Canvas, live: Live) {
        when (live) {
            is LiveShape -> drawShape(
                canvas, live.kind, live.x0, live.y0, live.x1, live.y1,
                live.color, live.width, live.style, live.angle
            )
            is LiveSelection -> {
                canvas.drawRect(live.src, fillPaint(live.paperColor))
                drawPiece(canvas, live.piece, live.box(), live.angle)
            }
            is LiveText -> drawTextBox(canvas, live.text, live.box(), live.color, live.size, live.angle)
        }
    }

    /**
     * A dashed box around the shape that is still adjustable. Without it nothing
     * on screen says that this one shape can still be moved and the rest cannot.
     */
    private fun drawLiveMarker(canvas: Canvas, live: Live) {
        val inset = 8f
        canvas.save()
        canvas.rotate(live.angle, live.centerX, live.centerY)
        val box = live.box()
        box.inset(-inset, -inset)
        drawMarquee(canvas, box)
        // Only the text box is resized by a handle. Shapes and selections are
        // pinched instead, and a handle on them would be a second way to do a
        // thing that already works.
        if (live is LiveText) {
            canvas.drawCircle(box.right, box.bottom, handleRadius(), fillPaint(HANDLE_COLOR))
        }
        canvas.restore()
    }

    private fun drawMarquee(canvas: Canvas, box: RectF) {
        val density = resources.displayMetrics.density
        val path = Path().apply { addRect(box, Path.Direction.CW) }
        liveOutlineHalo.strokeWidth = 6f * density
        liveOutlinePaint.strokeWidth = 3f * density
        canvas.drawPath(path, liveOutlineHalo)
        canvas.drawPath(path, liveOutlinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onCanvasTouched?.invoke()
                activePointerId = event.getPointerId(0)
                val x = event.x
                val y = event.y
                downX = x; downY = y; lastX = x; lastY = y

                // Touching the live shape or selection adjusts it; touching anywhere
                // else settles it and starts a new gesture there. No handles, no done
                // button: the next thing the child does is the confirmation.
                val current = live
                if (current is LiveText && current.onHandle(x, y, handleRadius() * 2f)) {
                    resizing = true
                    adjusting = false
                    dragging = false
                    return true
                }
                if (current != null && current.contains(x, y, touchMargin())) {
                    adjusting = true
                    pinching = false
                    dragging = false
                    return true
                }
                commitLive()

                adjusting = false
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
            // The single exception is a shape being adjusted, where the second finger
            // is the whole point: pinch to resize, twist to rotate.
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (adjusting && event.pointerCount >= 2) {
                    pinching = true
                    pinchDistance = spanBetween(event)
                    pinchAngle = angleBetween(event)
                    // Re-anchor on the centroid. Leaving the anchor on the first
                    // finger made the shape jump by the gap between them the moment
                    // the second finger landed.
                    lastX = (event.getX(0) + event.getX(1)) / 2f
                    lastY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (resizing) {
                    resizeLiveText(event)
                    return true
                }
                if (adjusting) {
                    adjustLiveShape(event)
                    return true
                }
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
                if (adjusting) {
                    // Back to one finger: re-anchor on whichever pointer is left, or the
                    // shape jumps by the distance between the two.
                    pinching = false
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(remaining)
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    return true
                }
                // The drawing finger lifted while another is still down. End the gesture
                // here: without this the stroke stayed live and the next MOVE snapped it
                // across the paper to whichever pointer became index 0.
                if (event.getPointerId(event.actionIndex) != activePointerId) return true
                endGesture(event.getX(event.actionIndex), event.getY(event.actionIndex))
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (resizing) {
                    resizing = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    return true
                }
                if (adjusting) {
                    adjusting = false
                    pinching = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    // A tap that never moved is not a drag but a request to retype:
                    // there is nowhere else to put "edit this text" that a child
                    // would find, and on anything else it does nothing.
                    if (abs(lastX - downX) < TAP_SLOP && abs(lastY - downY) < TAP_SLOP) {
                        liveText?.let { requestTextEdit(it) }
                    }
                    return true
                }
                if (!dragging) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) cancelGesture() else endGesture(event.getX(index), event.getY(index))
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                adjusting = false
                resizing = false
                pinching = false
                cancelGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * One finger drags the live shape; two pinch and twist it about its centre.
     *
     * Both are applied incrementally against the previous event rather than
     * against the start of the gesture, so lifting one finger mid-pinch cannot
     * snap the shape back to where the pinch began.
     */
    private fun adjustLiveShape(event: MotionEvent) {
        val live = live ?: return

        if (pinching && event.pointerCount >= 2) {
            val span = spanBetween(event)
            val angle = angleBetween(event)
            if (pinchDistance > MIN_PINCH_SPAN && span > MIN_PINCH_SPAN) {
                live.scaleBy(span / pinchDistance)
            }
            live.angle += angle - pinchAngle
            pinchDistance = span
            pinchAngle = angle
            // The pair of fingers also carries the shape around with it.
            val cx = (event.getX(0) + event.getX(1)) / 2f
            val cy = (event.getY(0) + event.getY(1)) / 2f
            live.translate(cx - lastX, cy - lastY)
            lastX = cx
            lastY = cy
            invalidate()
            return
        }

        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        val x = event.getX(index)
        val y = event.getY(index)
        live.translate(x - lastX, y - lastY)
        lastX = x
        lastY = y
        invalidate()
    }

    private fun spanBetween(event: MotionEvent): Float =
        hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

    private fun angleBetween(event: MotionEvent): Float = Math.toDegrees(
        atan2(
            (event.getY(1) - event.getY(0)).toDouble(),
            (event.getX(1) - event.getX(0)).toDouble()
        )
    ).toFloat()

    /** A small shape still has to be grabbable by a fingertip. */
    private fun touchMargin(): Float = 24f * resources.displayMetrics.density

    private fun handleRadius(): Float = 11f * resources.displayMetrics.density

    /**
     * The smallest selection worth having, in real millimetres rather than pixels.
     *
     * A box measured in pixels is a different size on every phone, and the one it
     * came out smallest on is the one where a child could not grab it. Clamped
     * because a handful of devices report a nonsense [DisplayMetrics.xdpi].
     */
    private fun minSelectionPx(): Float {
        val density = resources.displayMetrics.density
        return TypedValue
            .applyDimension(TypedValue.COMPLEX_UNIT_MM, MIN_SELECTION_MM, resources.displayMetrics)
            .coerceIn(48f * density, 240f * density)
    }

    /**
     * Drags the corner of the text box. Only the width is really an input — the
     * height follows the wrapped text — so this is how the child forces a line
     * break without touching the keyboard.
     */
    private fun resizeLiveText(event: MotionEvent) {
        val text = liveText ?: return
        val local = text.toLocal(event.x, event.y)
        text.x1 = local.x
        text.y1 = local.y
        text.reflow()
        invalidate()
    }

    private fun requestTextEdit(text: LiveText) {
        editingText = text
        pendingTextBox = text.box()
        onTextRequested?.invoke(text.text)
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
                // Dragging a box picks the column the text will wrap into; a plain
                // tap gets a sensible default one, because a child who just wants a
                // word should not have to know about boxes at all.
                pendingTextBox = if (tappedInPlace) {
                    val half = width * DEFAULT_TEXT_WIDTH / 2f
                    RectF(x - half, y, x + half, y)
                } else {
                    RectF(minOf(downX, x), minOf(downY, y), maxOf(downX, x), maxOf(downY, y))
                }
                editingText = null
                invalidate()
                onTextRequested?.invoke("")
            }

            Tool.SELECT -> liftSelection(x, y)

            else -> {
                val kind = tool.asShape() ?: return
                // Ignore accidental zero-size shapes from a stray tap.
                if (tappedInPlace) {
                    invalidate()
                    return
                }
                live = LiveShape(kind, downX, downY, x, y, color, strokeWidth, shapeStyle)
                invalidate()
                onHistoryChanged?.invoke()
            }
        }
    }

    /**
     * Lifts the dragged rectangle of pixels off the picture so it can be carried
     * around, the way a selection works in every paint program.
     *
     * Nothing is cut yet: the pixels stay in the bitmap and the floating copy is
     * drawn over the hole. The cut only becomes real when the selection settles,
     * which is what makes dropping it a free undo.
     */
    private fun liftSelection(x: Float, y: Float) {
        val bmp = bitmap ?: return
        val rect = Rect(
            minOf(downX, x).roundToInt(), minOf(downY, y).roundToInt(),
            maxOf(downX, x).roundToInt(), maxOf(downY, y).roundToInt()
        )
        // A short drag is grown to the minimum rather than thrown away, and a plain
        // tap grabs a minimum-sized box around the finger. Rejecting them was the
        // reason selecting felt like it did nothing: a child's "drag" is a smudge.
        growToMinimum(rect, bmp)
        live = liftRect(rect)
        invalidate()
        onHistoryChanged?.invoke()
    }

    /**
     * Grows [rect] about its centre to at least the minimum selection size, then
     * slides it back inside the paper rather than letting it hang off the edge —
     * a box clipped to a sliver at the edge is the same unusable thing again.
     */
    private fun growToMinimum(rect: Rect, bmp: Bitmap) {
        val min = minSelectionPx().roundToInt()
        val width = minOf(min, bmp.width)
        val height = minOf(min, bmp.height)
        if (rect.width() < width) {
            val centerX = rect.centerX()
            rect.left = centerX - width / 2
            rect.right = rect.left + width
        }
        if (rect.height() < height) {
            val centerY = rect.centerY()
            rect.top = centerY - height / 2
            rect.bottom = rect.top + height
        }
        rect.offset(
            -minOf(rect.left, 0) - maxOf(rect.right - bmp.width, 0),
            -minOf(rect.top, 0) - maxOf(rect.bottom - bmp.height, 0)
        )
    }

    /** Lifts [rect] off the current bitmap, or null if it does not fit on it. */
    private fun liftRect(rect: Rect): LiveSelection? {
        val bmp = bitmap ?: return null
        if (!rect.intersect(0, 0, bmp.width, bmp.height)) return null
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val piece = Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
        // createBitmap returns the source itself for a whole-bitmap subset, and the
        // source keeps being painted on; freeze a copy so the piece stops changing.
        val frozen = if (piece === bmp) bmp.copy(Bitmap.Config.ARGB_8888, false) else piece
        return LiveSelection(rect, frozen, paperColor)
    }

    /**
     * Answers the text dialog. The result is not committed: like a shape it lands
     * as a live object, so it can be dragged, rewrapped and retyped before it
     * becomes part of the picture.
     */
    fun addText(text: String) {
        val box = pendingTextBox ?: return
        pendingTextBox = null
        val editing = editingText
        editingText = null

        if (editing != null) {
            if (text.isBlank()) {
                // Emptying the box is how you delete text you have not settled yet.
                discardLive()
                return
            }
            editing.text = text
            editing.reflow()
            invalidate()
            return
        }

        if (text.isBlank()) return
        commitLive()
        live = LiveText(text, box.left, box.top, box.right, box.top, color, textSize)
            .also { it.reflow() }
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** The dialog was dismissed without an answer; forget the box it was for. */
    fun cancelText() {
        pendingTextBox = null
        editingText = null
    }

    fun undo() {
        // A shape, selection or text that has not settled yet is undone by dropping
        // it, not by committing it first and then walking history back over it. For
        // a selection that is exactly right: the pixels it lifted never left the
        // bitmap, so dropping it puts the picture back as it was.
        val current = live
        if (current != null) {
            live = null
            if (current.toOp() != null) {
                invalidate()
                onHistoryChanged?.invoke()
                return
            }
            // An untouched selection records nothing, so dropping it changed nothing
            // on screen. Undo restores one of those after a move; stopping here would
            // spend the press without the picture moving, so walk history instead.
        }
        if (ops.isEmpty()) {
            invalidate()
            onHistoryChanged?.invoke()
            return
        }
        val undone = ops.removeAt(ops.lastIndex)
        redoStack.add(undone)
        dropCheckpointsAfter(ops.size)
        replayAll()
        // Undoing a move or a caption hands the object back rather than throwing it
        // away: both are placed by eye, and "not quite there" is the usual reason
        // for undoing one. Everything else stays a plain step backwards.
        live = when (undone) {
            is MoveOp -> liftRect(Rect(undone.src))
            is TextOp -> LiveText(
                undone.text, undone.box.left, undone.box.top, undone.box.right,
                undone.box.top, undone.color, undone.size
            ).also { it.angle = undone.angle; it.reflow() }
            else -> null
        }
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun redo() {
        // Settling a shape is new work, and new work clears the redo stack — the
        // same rule every other committed op follows.
        commitLive()
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
        // Settle first, so undoing the clear brings the shape back with the rest.
        commitLive()
        commit(ClearOp(paperColor))
    }

    /** A copy, so the caller can hand it to an encoder without racing the canvas. */
    fun exportBitmap(): Bitmap? {
        val current = live
        // Mutable only when there is something live to paint in; an immutable copy
        // is the better thing to hand an encoder, and Canvas rejects one outright.
        val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, current != null) ?: return null
        // The live shape is painted into the exported copy rather than committed.
        // Autosave runs off this every 1.2 s, and committing here settled the shape
        // before the child could touch it — the adjustable window never opened.
        // Drawing it means a kill still saves the shape; it comes back as part of
        // the restored paper, which is where it would have ended up anyway.
        current?.let { drawLive(Canvas(copy), it) }
        return copy
    }

    private companion object {
        const val TAP_SLOP = 6f
        /** Below this the two fingers are close enough that the ratio is noise. */
        const val MIN_PINCH_SPAN = 24f
        /** Anything smaller than this is a slip of the finger, not a selection. */
        /** Two centimetres of real paper, whatever the screen density says. */
        const val MIN_SELECTION_MM = 20f
        /** Text is sized off the brush slider; this is the multiplier between them. */
        const val TEXT_SIZE_FACTOR = 4f
        /** A tapped text box gets this share of the paper width to wrap into. */
        const val DEFAULT_TEXT_WIDTH = 0.7f
        /** Narrower than this and the box can no longer fit a single letter. */
        const val MIN_TEXT_WIDTH = 40f
        const val HANDLE_COLOR = 0xCC5A4632.toInt()
        const val CHECKPOINT_INTERVAL = 12
        const val MAX_CHECKPOINTS = 2
    }
}
